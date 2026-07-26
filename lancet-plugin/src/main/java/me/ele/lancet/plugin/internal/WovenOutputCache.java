package me.ele.lancet.plugin.internal;

import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Persists woven class/resource bytes keyed by jar entry name.
 */
public class WovenOutputCache {

    private final File classCacheDir;

    public WovenOutputCache(File lancetDir) {
        this.classCacheDir = new File(lancetDir, "wovenClasses");
    }

    public File getClassCacheDir() {
        return classCacheDir;
    }

    /**
     * Stores a woven entry.
     */
    public void putClassEntry(String entryName, byte[] bytes) throws IOException {
        File target = new File(classCacheDir, entryName);
        Files.createParentDirs(target);
        Files.write(bytes, target);
    }
}
