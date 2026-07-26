package me.ele.lancet.plugin.internal;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Persists woven jar/class bytes so unchanged inputs can be merged without re-weaving.
 */
public class WovenOutputCache {

    private final File jarCacheDir;
    private final File classCacheDir;

    public WovenOutputCache(File lancetDir) {
        this.jarCacheDir = new File(lancetDir, "wovenJars");
        this.classCacheDir = new File(lancetDir, "wovenClasses");
    }

    public File getClassCacheDir() {
        return classCacheDir;
    }

    /**
     * Returns the cache file for a woven input jar.
     */
    public File getJarCacheFile(File inputJar) {
        String cacheName = Hashing.sha1()
                .hashString(inputJar.getAbsolutePath(), StandardCharsets.UTF_8)
                .toString() + ".jar";
        return new File(jarCacheDir, cacheName);
    }

    /**
     * Copies all entries from a cached woven jar into the consumer.
     */
    public void forEachJarEntry(File inputJar, EntryConsumer consumer) throws IOException {
        File cacheFile = getJarCacheFile(inputJar);
        if (!cacheFile.isFile()) {
            return;
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(cacheFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                consumer.accept(entry.getName(), ByteStreams.toByteArray(zis));
            }
        }
    }

    /**
     * Returns whether a woven jar cache exists for the given input.
     */
    public boolean hasJarCache(File inputJar) {
        return getJarCacheFile(inputJar).isFile();
    }

    /**
     * Opens a jar cache writer for the given input jar.
     */
    public JarCacheWriter openJarCacheWriter(File inputJar) throws IOException {
        Files.createParentDirs(getJarCacheFile(inputJar));
        return new JarCacheWriter(getJarCacheFile(inputJar));
    }

    /**
     * Reads a cached woven class entry.
     */
    public byte[] getClassEntry(String entryName) throws IOException {
        File target = new File(classCacheDir, entryName);
        if (!target.isFile()) {
            return null;
        }
        return Files.toByteArray(target);
    }

    /**
     * Stores a woven class entry.
     */
    public void putClassEntry(String entryName, byte[] bytes) throws IOException {
        File target = new File(classCacheDir, entryName);
        Files.createParentDirs(target);
        Files.write(bytes, target);
    }

    public interface EntryConsumer {
        void accept(String entryName, byte[] bytes) throws IOException;
    }

    /**
     * Writes woven entries for one input jar into the cache file.
     */
    public static final class JarCacheWriter implements AutoCloseable {

        private final java.util.jar.JarOutputStream jarOutputStream;

        JarCacheWriter(File cacheFile) throws IOException {
            jarOutputStream = new java.util.jar.JarOutputStream(
                    new BufferedOutputStream(new FileOutputStream(cacheFile)));
        }

        public void writeEntry(String entryName, byte[] bytes) throws IOException {
            ZipEntry entry = new ZipEntry(entryName);
            jarOutputStream.putNextEntry(entry);
            jarOutputStream.write(bytes);
            jarOutputStream.closeEntry();
        }

        @Override
        public void close() throws IOException {
            jarOutputStream.close();
        }
    }
}
