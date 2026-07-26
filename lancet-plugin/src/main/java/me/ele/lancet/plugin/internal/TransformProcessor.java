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
 * Writes transformed classes into a single merged jar, with entry level woven output cache.
 */
public class TransformProcessor implements ClassFetcher, Closeable {

    private final Weaver weaver;
    private final File outputFile;
    private final WovenOutputCache wovenCache;
    private final Object outputLock = new Object();
    private final Set<String> writtenEntries = ConcurrentHashMap.newKeySet();
    private JarOutputStream jarOutputStream;

    public TransformProcessor(TransformContext context, Weaver weaver, WovenOutputCache wovenCache) {
        this.weaver = weaver;
        this.outputFile = context.getOutputFile();
        this.wovenCache = wovenCache;
    }

    /**
     * Restores unchanged class/resource entries from the woven cache.
     */
    public void restoreUnchangedEntries(Set<String> changedClassEntries) throws IOException {
        File classCacheDir = wovenCache.getClassCacheDir();
        if (!classCacheDir.isDirectory()) {
            return;
        }
        int restored = 0;
        for (File file : Files.fileTraverser().depthFirstPreOrder(classCacheDir)) {
            if (!file.isFile()) {
                continue;
            }
            String entryName = classCacheDir.toURI().relativize(file.toURI()).toString();
            if (changedClassEntries.contains(entryName)) {
                continue;
            }
            writeEntry(entryName, Files.toByteArray(file));
            restored++;
        }
        Log.i("TransformProcessor: restored " + restored + " cached entries");
    }

    @Override
    public boolean onStart(QualifiedContent content) throws IOException {
        if (content instanceof JarInput) {
            JarInput jarInput = (JarInput) content;
            if (jarInput.getStatus() == Status.REMOVED || jarInput.getStatus() == Status.NOTCHANGED) {
                return false;
            }
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
            wovenCache.putClassEntry(relativePath, bytes);
            return;
        }
        for (ClassData classData : weaver.weave(bytes, relativePath)) {
            String entryName = classData.getClassName() + ".class";
            byte[] classBytes = classData.getClassBytes();
            writeEntry(entryName, classBytes);
            wovenCache.putClassEntry(entryName, classBytes);
        }
    }

    @Override
    public void onComplete(QualifiedContent content) {
    }

    @Override
    public void close() throws IOException {
        synchronized (outputLock) {
            if (jarOutputStream != null) {
                jarOutputStream.close();
                jarOutputStream = null;
            }
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
