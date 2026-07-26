package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.google.common.io.Files;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import me.ele.lancet.plugin.internal.context.ClassFetcher;
import me.ele.lancet.weaver.ClassData;
import me.ele.lancet.weaver.Weaver;
import me.ele.lancet.weaver.internal.log.Log;

/**
 * Writes transformed classes into a single merged jar, with optional woven output cache.
 */
public class TransformProcessor implements ClassFetcher, Closeable {

    private final Weaver weaver;
    private final File outputFile;
    private final WovenOutputCache wovenCache;
    private final Object outputLock = new Object();
    private final Set<String> writtenEntries = ConcurrentHashMap.newKeySet();
    private final Map<QualifiedContent, WovenOutputCache.JarCacheWriter> jarCacheWriters = new ConcurrentHashMap<>();
    private JarOutputStream jarOutputStream;

    public TransformProcessor(TransformContext context, Weaver weaver, WovenOutputCache wovenCache) {
        this.weaver = weaver;
        this.outputFile = context.getOutputFile();
        this.wovenCache = wovenCache;
    }

    /**
     * Restores unchanged jars directly from the woven jar cache.
     */
    public void restoreCachedJars(Collection<JarInput> jarInputs) throws IOException {
        for (JarInput jarInput : jarInputs) {
            if (jarInput.getStatus() != Status.NOTCHANGED) {
                continue;
            }
            File inputJar = jarInput.getFile();
            if (!wovenCache.hasJarCache(inputJar)) {
                Log.i("Woven jar cache miss, will reprocess: " + inputJar.getName());
                continue;
            }
            wovenCache.forEachJarEntry(inputJar, this::writeEntry);
        }
    }

    /**
     * Restores unchanged directory classes from the class cache.
     */
    public void restoreCachedDirectoryClasses(TransformContext context) throws IOException {
        Set<String> changedEntries = collectChangedDirectoryEntries(context.getAllDirs());
        File classCacheDir = wovenCache.getClassCacheDir();
        if (!classCacheDir.isDirectory()) {
            return;
        }
        for (File file : Files.fileTraverser().depthFirstPreOrder(classCacheDir)) {
            if (!file.isFile() || !file.getName().endsWith(".class")) {
                continue;
            }
            String entryName = classCacheDir.toURI().relativize(file.toURI()).toString();
            if (changedEntries.contains(entryName)) {
                continue;
            }
            writeEntry(entryName, Files.toByteArray(file));
        }
    }

    @Override
    public boolean onStart(QualifiedContent content) throws IOException {
        if (content instanceof JarInput) {
            JarInput jarInput = (JarInput) content;
            if (jarInput.getStatus() == Status.REMOVED) {
                return false;
            }
            if (jarInput.getStatus() == Status.NOTCHANGED) {
                return false;
            }
            ensureOutputOpen();
            jarCacheWriters.put(content, wovenCache.openJarCacheWriter(jarInput.getFile()));
            return true;
        }
        ensureOutputOpen();
        return true;
    }

    @Override
    public void onClassFetch(QualifiedContent content, Status status, String relativePath, byte[] bytes) throws IOException {
        if (status == Status.REMOVED) {
            return;
        }
        if (!relativePath.endsWith(".class")) {
            writeEntry(relativePath, bytes);
            writeJarCacheEntry(content, relativePath, bytes);
            return;
        }
        for (ClassData classData : weaver.weave(bytes, relativePath)) {
            String entryName = classData.getClassName() + ".class";
            byte[] classBytes = classData.getClassBytes();
            writeEntry(entryName, classBytes);
            writeJarCacheEntry(content, entryName, classBytes);
            if (!(content instanceof JarInput)) {
                wovenCache.putClassEntry(entryName, classBytes);
            }
        }
    }

    @Override
    public void onComplete(QualifiedContent content) throws IOException {
        WovenOutputCache.JarCacheWriter writer = jarCacheWriters.remove(content);
        if (writer != null) {
            writer.close();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (outputLock) {
            if (jarOutputStream != null) {
                jarOutputStream.close();
                jarOutputStream = null;
            }
        }
        for (WovenOutputCache.JarCacheWriter writer : jarCacheWriters.values()) {
            writer.close();
        }
        jarCacheWriters.clear();
    }

    private Set<String> collectChangedDirectoryEntries(Collection<DirectoryInput> directoryInputs) {
        Set<String> changedEntries = new HashSet<>();
        for (DirectoryInput directoryInput : directoryInputs) {
            URI base = directoryInput.getFile().toURI();
            for (Map.Entry<File, Status> entry : directoryInput.getChangedFiles().entrySet()) {
                if (entry.getValue() == Status.REMOVED) {
                    continue;
                }
                changedEntries.add(base.relativize(entry.getKey().toURI()).toString());
            }
        }
        return changedEntries;
    }

    private void writeJarCacheEntry(QualifiedContent content, String entryName, byte[] bytes) throws IOException {
        WovenOutputCache.JarCacheWriter writer = jarCacheWriters.get(content);
        if (writer != null) {
            writer.writeEntry(entryName, bytes);
        }
    }

    private void ensureOutputOpen() throws IOException {
        synchronized (outputLock) {
            if (jarOutputStream == null) {
                Files.createParentDirs(outputFile);
                jarOutputStream = new JarOutputStream(
                        new BufferedOutputStream(new FileOutputStream(outputFile)));
            }
        }
    }

    private void writeEntry(String entryName, byte[] bytes) throws IOException {
        ensureOutputOpen();
        synchronized (outputLock) {
            if (!writtenEntries.add(entryName)) {
                return;
            }
            ZipEntry entry = new ZipEntry(entryName);
            jarOutputStream.putNextEntry(entry);
            jarOutputStream.write(bytes);
            jarOutputStream.closeEntry();
        }
    }
}
