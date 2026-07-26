package me.ele.lancet.plugin.internal;

import java.io.File;
import java.util.zip.CRC32;

/**
 * Lightweight fingerprint for files or class entry bytes.
 */
public class FileFingerprint {

    public long hash;
    public long size;

    public FileFingerprint() {
    }

    public FileFingerprint(long hash, long size) {
        this.hash = hash;
        this.size = size;
    }

    public static FileFingerprint ofFile(File file) {
        return new FileFingerprint(file.lastModified(), file.length());
    }

    public static FileFingerprint ofBytes(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return new FileFingerprint(crc32.getValue(), bytes.length);
    }

    public boolean matchesBytes(byte[] bytes) {
        return bytes.length == size && ofBytes(bytes).hash == hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileFingerprint)) {
            return false;
        }
        FileFingerprint that = (FileFingerprint) o;
        return hash == that.hash && size == that.size;
    }

    @Override
    public int hashCode() {
        return (int) (hash ^ (hash >>> 32) ^ size);
    }
}
