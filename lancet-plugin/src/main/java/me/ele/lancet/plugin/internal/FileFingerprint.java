package me.ele.lancet.plugin.internal;

/**
 * Lightweight file fingerprint used to detect input changes without AGP Transform status.
 */
public class FileFingerprint {

    public long lastModified;
    public long size;

    public FileFingerprint() {
    }

    public FileFingerprint(long lastModified, long size) {
        this.lastModified = lastModified;
        this.size = size;
    }

    public static FileFingerprint of(java.io.File file) {
        return new FileFingerprint(file.lastModified(), file.length());
    }

    public boolean matches(java.io.File file) {
        return file.lastModified() == lastModified && file.length() == size;
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
        return lastModified == that.lastModified && size == that.size;
    }

    @Override
    public int hashCode() {
        return (int) (lastModified ^ (lastModified >>> 32) ^ size);
    }
}
