package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.Status;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import org.apache.commons.io.Charsets;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.ele.lancet.plugin.internal.preprocess.MetaGraphGeneratorImpl;
import me.ele.lancet.weaver.internal.graph.CheckFlow;
import me.ele.lancet.weaver.internal.graph.ClassEntity;

/**
 * Persistent cache for Lancet incremental compilation.
 */
public class LocalCache {

    private static final Type FINGERPRINT_MAP_TYPE = new TypeToken<Map<String, FileFingerprint>>() {
    }.getType();

    private final File localCache;
    private final File fingerprintCache;
    private final Metas metas;
    private Map<String, FileFingerprint> entryFingerprints = Collections.emptyMap();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public LocalCache(File dir) {
        localCache = new File(dir, "buildCache.json");
        fingerprintCache = new File(dir, "entryFingerprints.json");
        metas = loadCache();
        entryFingerprints = loadFingerprints();
    }

    private Metas loadCache() {
        if (localCache.exists() && localCache.isFile()) {
            try {
                Reader reader = Files.newReader(localCache, Charsets.UTF_8);
                return gson.fromJson(reader, Metas.class).withoutNull();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (JsonParseException e) {
                if (!localCache.delete()) {
                    throw new RuntimeException("cache file has been modified, but can't delete.", e);
                }
            }
        }
        return new Metas();
    }

    private Map<String, FileFingerprint> loadFingerprints() {
        if (!fingerprintCache.isFile()) {
            Map<String, FileFingerprint> legacy = metas.fingerprints;
            return legacy == null ? Collections.emptyMap() : legacy;
        }
        try {
            Reader reader = Files.newReader(fingerprintCache, Charsets.UTF_8);
            Map<String, FileFingerprint> loaded = gson.fromJson(reader, FINGERPRINT_MAP_TYPE);
            return loaded == null ? Collections.emptyMap() : loaded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (JsonParseException e) {
            if (!fingerprintCache.delete()) {
                throw new RuntimeException("fingerprint cache has been modified, but can't delete.", e);
            }
            return Collections.emptyMap();
        }
    }

    public List<String> hookClasses() {
        return metas.hookClasses;
    }

    public List<String> hookClassesInDir() {
        return metas.hookClassesInDir;
    }

    public CheckFlow hookFlow() {
        return metas.flow;
    }

    public Map<String, FileFingerprint> getFingerprints() {
        return entryFingerprints;
    }

    public void updateFingerprints(Map<String, FileFingerprint> fingerprints) {
        entryFingerprints = fingerprints == null ? Collections.emptyMap() : new LinkedHashMap<>(fingerprints);
        metas.fingerprints = entryFingerprints;
    }

    /**
     * Returns true when any hook class entry changed in this compilation.
     */
    public boolean isHookClassModified(TransformContext context) {
        Set<String> changedEntries = context.getChangedClassEntries();
        if (changedEntries.isEmpty()) {
            return false;
        }
        for (String hookClass : metas.hookClasses) {
            if (changedEntries.contains(hookClass + ".class")) {
                return true;
            }
        }
        if (metas.hookClassesInDir == null || metas.hookClassesInDir.isEmpty()) {
            return false;
        }
        Set<String> hookClassPaths = metas.hookClassesInDir.stream().collect(Collectors.toSet());
        for (com.android.build.api.transform.DirectoryInput directoryInput : context.getAllDirs()) {
            for (Map.Entry<File, Status> entry : directoryInput.getChangedFiles().entrySet()) {
                if (hookClassPaths.contains(entry.getKey().getAbsolutePath())
                        && entry.getValue() != Status.NOTCHANGED) {
                    return true;
                }
            }
        }
        return false;
    }

    public void accept(MetaGraphGeneratorImpl graph) {
        metas.classMetas.forEach(m -> graph.add(m, Status.NOTCHANGED));
    }

    public void saveToLocal() {
        saveCacheFile(localCache, metas.withoutNull(), Metas.class);
        saveCacheFile(fingerprintCache, entryFingerprints, FINGERPRINT_MAP_TYPE);
    }

    private void saveCacheFile(File target, Object data, Type type) {
        try {
            Files.createParentDirs(target);
            Writer writer = Files.newWriter(target, Charsets.UTF_8);
            gson.toJson(data, type, writer);
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void clear() throws IOException {
        if (localCache.exists() && localCache.isFile() && !localCache.delete()) {
            throw new IOException("can't delete cache file");
        }
        if (fingerprintCache.exists() && fingerprintCache.isFile() && !fingerprintCache.delete()) {
            throw new IOException("can't delete fingerprint cache file");
        }
        entryFingerprints = Collections.emptyMap();
        metas.fingerprints = Collections.emptyMap();
    }

    public void savePartially(List<ClassEntity> classMetas) {
        metas.classMetas = classMetas;
        saveToLocal();
    }

    public void saveFully(List<ClassEntity> classMetas, List<String> hookClasses, List<String> hookClassesInDir,
                          List<String> jarWithHookClasses) {
        metas.classMetas = classMetas;
        metas.hookClasses = hookClasses;
        metas.hookClassesInDir = hookClassesInDir;
        metas.jarsWithHookClasses = jarWithHookClasses;
        saveToLocal();
    }
}
