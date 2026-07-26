package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.Status;
import com.google.common.io.Files;

import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.ele.lancet.plugin.internal.transform.SimpleDirectoryInput;
import me.ele.lancet.plugin.internal.transform.SimpleJarInput;

/**
 * Detects jar/directory input changes for AGP 8 artifact transforms.
 * Uses persisted fingerprints because ScopedArtifacts does not expose per-input Status.
 */
public final class InputChangeDetector {

    private InputChangeDetector() {
    }

    /**
     * Result of input change detection.
     */
    public static final class Result {
        private final List<JarInput> jarInputs;
        private final List<DirectoryInput> directoryInputs;
        private final boolean incremental;
        private final Map<String, FileFingerprint> fingerprints;

        Result(List<JarInput> jarInputs, List<DirectoryInput> directoryInputs,
               boolean incremental, Map<String, FileFingerprint> fingerprints) {
            this.jarInputs = jarInputs;
            this.directoryInputs = directoryInputs;
            this.incremental = incremental;
            this.fingerprints = fingerprints;
        }

        public List<JarInput> getJarInputs() {
            return jarInputs;
        }

        public List<DirectoryInput> getDirectoryInputs() {
            return directoryInputs;
        }

        public boolean isIncremental() {
            return incremental;
        }

        public Map<String, FileFingerprint> getFingerprints() {
            return fingerprints;
        }
    }

    public static Result detect(InputChanges inputChanges,
                                ListProperty<RegularFile> allJars,
                                ListProperty<Directory> allDirs,
                                boolean enableIncremental,
                                LocalCache cache) throws IOException {
        List<File> currentJars = new ArrayList<>();
        for (RegularFile regularFile : allJars.get()) {
            currentJars.add(regularFile.getAsFile());
        }
        List<File> currentDirs = new ArrayList<>();
        for (Directory directory : allDirs.get()) {
            currentDirs.add(directory.getAsFile());
        }

        Map<String, FileFingerprint> previousFingerprints = cache.getFingerprints();
        Map<String, FileFingerprint> nextFingerprints = new LinkedHashMap<>();

        if (!enableIncremental || !inputChanges.isIncremental() || previousFingerprints.isEmpty()) {
            return buildFullResult(currentJars, currentDirs, nextFingerprints);
        }

        Set<String> currentJarPaths = new HashSet<>();
        List<JarInput> jarInputs = new ArrayList<>();
        for (File jar : currentJars) {
            currentJarPaths.add(jar.getAbsolutePath());
            Status status = resolveJarStatus(jar, previousFingerprints);
            jarInputs.add(new SimpleJarInput(jar, status));
            nextFingerprints.put(jar.getAbsolutePath(), FileFingerprint.of(jar));
        }
        for (Map.Entry<String, FileFingerprint> entry : previousFingerprints.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".jar") || currentJarPaths.contains(path)) {
                continue;
            }
            jarInputs.add(new SimpleJarInput(new File(path), Status.REMOVED));
        }

        List<DirectoryInput> directoryInputs = new ArrayList<>(currentDirs.size());
        for (File dir : currentDirs) {
            Map<File, Status> changedFiles = detectDirectoryChanges(dir, previousFingerprints, nextFingerprints);
            directoryInputs.add(new SimpleDirectoryInput(dir, changedFiles));
        }

        return new Result(jarInputs, directoryInputs, true, nextFingerprints);
    }

    private static Result buildFullResult(List<File> currentJars,
                                          List<File> currentDirs,
                                          Map<String, FileFingerprint> nextFingerprints) throws IOException {
        List<JarInput> jarInputs = new ArrayList<>(currentJars.size());
        for (File jar : currentJars) {
            jarInputs.add(new SimpleJarInput(jar, Status.ADDED));
            nextFingerprints.put(jar.getAbsolutePath(), FileFingerprint.of(jar));
        }

        List<DirectoryInput> directoryInputs = new ArrayList<>(currentDirs.size());
        for (File dir : currentDirs) {
            collectDirectoryFingerprints(dir, nextFingerprints);
            directoryInputs.add(new SimpleDirectoryInput(dir, new HashMap<>()));
        }
        return new Result(jarInputs, directoryInputs, false, nextFingerprints);
    }

    private static Status resolveJarStatus(File jar, Map<String, FileFingerprint> previousFingerprints) {
        FileFingerprint previous = previousFingerprints.get(jar.getAbsolutePath());
        if (previous == null) {
            return Status.ADDED;
        }
        if (!previous.matches(jar)) {
            return Status.CHANGED;
        }
        return Status.NOTCHANGED;
    }

    private static Map<File, Status> detectDirectoryChanges(File dir,
                                                            Map<String, FileFingerprint> previousFingerprints,
                                                            Map<String, FileFingerprint> nextFingerprints) throws IOException {
        Map<File, Status> changedFiles = new HashMap<>();
        Set<String> currentClassPaths = new HashSet<>();
        for (File file : Files.fileTraverser().depthFirstPreOrder(dir)) {
            if (!file.isFile() || !file.getName().endsWith(".class")) {
                continue;
            }
            String absolutePath = file.getAbsolutePath();
            currentClassPaths.add(absolutePath);
            FileFingerprint current = FileFingerprint.of(file);
            nextFingerprints.put(absolutePath, current);
            FileFingerprint previous = previousFingerprints.get(absolutePath);
            if (previous == null) {
                changedFiles.put(file, Status.ADDED);
            } else if (!previous.equals(current)) {
                changedFiles.put(file, Status.CHANGED);
            }
        }

        String dirPath = dir.getAbsolutePath();
        for (Map.Entry<String, FileFingerprint> entry : previousFingerprints.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".class") || !path.startsWith(dirPath)) {
                continue;
            }
            if (!currentClassPaths.contains(path)) {
                changedFiles.put(new File(path), Status.REMOVED);
            }
        }
        return changedFiles;
    }

    private static void collectDirectoryFingerprints(File dir, Map<String, FileFingerprint> nextFingerprints) throws IOException {
        for (File file : Files.fileTraverser().depthFirstPreOrder(dir)) {
            if (file.isFile() && file.getName().endsWith(".class")) {
                nextFingerprints.put(file.getAbsolutePath(), FileFingerprint.of(file));
            }
        }
    }
}
