package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.Status;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import me.ele.lancet.plugin.internal.transform.SimpleDirectoryInput;
import me.ele.lancet.plugin.internal.transform.SimpleJarInput;
import me.ele.lancet.weaver.internal.log.Log;

/**
 * Detects class entry level changes for AGP 8 merged jar inputs.
 */
public final class InputChangeDetector {

    static final String JAR_CONTAINER_PREFIX = "jar:";

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
        private final Set<String> changedClassEntries;

        Result(List<JarInput> jarInputs, List<DirectoryInput> directoryInputs,
               boolean incremental, Map<String, FileFingerprint> fingerprints,
               Set<String> changedClassEntries) {
            this.jarInputs = jarInputs;
            this.directoryInputs = directoryInputs;
            this.incremental = incremental;
            this.fingerprints = fingerprints;
            this.changedClassEntries = changedClassEntries;
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

        public Set<String> getChangedClassEntries() {
            return changedClassEntries;
        }
    }

    public static Result detect(ListProperty<RegularFile> allJars,
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
        Set<String> changedClassEntries = new HashSet<>();

        if (!enableIncremental || previousFingerprints.isEmpty()) {
            return buildFullResult(currentJars, currentDirs, nextFingerprints, changedClassEntries);
        }

        List<JarInput> jarInputs = new ArrayList<>(currentJars.size());
        for (File jar : currentJars) {
            String containerKey = jarContainerKey(jar);
            nextFingerprints.put(containerKey, FileFingerprint.ofFile(jar));
            Map<String, Status> changedEntries = detectJarEntryChanges(
                    jar, previousFingerprints, nextFingerprints, changedClassEntries);
            Status jarStatus = changedEntries.isEmpty() ? Status.NOTCHANGED : Status.CHANGED;
            jarInputs.add(new SimpleJarInput(jar, jarStatus, changedEntries));
        }

        List<DirectoryInput> directoryInputs = new ArrayList<>(currentDirs.size());
        for (File dir : currentDirs) {
            Map<File, Status> changedFiles = detectDirectoryChanges(
                    dir, previousFingerprints, nextFingerprints, changedClassEntries);
            directoryInputs.add(new SimpleDirectoryInput(dir, changedFiles));
        }

        Log.i("InputChangeDetector: changed class entries = " + changedClassEntries.size()
                + ", total fingerprints = " + nextFingerprints.size());
        return new Result(jarInputs, directoryInputs, true, nextFingerprints, changedClassEntries);
    }

    private static Result buildFullResult(List<File> currentJars,
                                          List<File> currentDirs,
                                          Map<String, FileFingerprint> nextFingerprints,
                                          Set<String> changedClassEntries) throws IOException {
        List<JarInput> jarInputs = new ArrayList<>(currentJars.size());
        for (File jar : currentJars) {
            jarInputs.add(new SimpleJarInput(jar, Status.ADDED, new HashMap<>()));
            collectJarFingerprints(jar, nextFingerprints, changedClassEntries, true);
        }

        List<DirectoryInput> directoryInputs = new ArrayList<>(currentDirs.size());
        for (File dir : currentDirs) {
            collectDirectoryFingerprints(dir, nextFingerprints, changedClassEntries, true);
            directoryInputs.add(new SimpleDirectoryInput(dir, new HashMap<>()));
        }
        return new Result(jarInputs, directoryInputs, false, nextFingerprints, changedClassEntries);
    }

    private static Map<String, Status> detectJarEntryChanges(File jar,
                                                             Map<String, FileFingerprint> previousFingerprints,
                                                             Map<String, FileFingerprint> nextFingerprints,
                                                             Set<String> changedClassEntries) throws IOException {
        Map<String, Status> changedEntries = new HashMap<>();
        Set<String> currentEntryNames = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = ByteStreams.toByteArray(zis);
                String entryName = entry.getName();
                currentEntryNames.add(entryName);
                FileFingerprint current = FileFingerprint.ofBytes(data);
                nextFingerprints.put(entryName, current);
                FileFingerprint previous = previousFingerprints.get(entryName);
                if (previous == null) {
                    changedEntries.put(entryName, Status.ADDED);
                    if (entryName.endsWith(".class")) {
                        changedClassEntries.add(entryName);
                    }
                } else if (!previous.equals(current)) {
                    changedEntries.put(entryName, Status.CHANGED);
                    if (entryName.endsWith(".class")) {
                        changedClassEntries.add(entryName);
                    }
                }
            }
        }

        for (Map.Entry<String, FileFingerprint> previousEntry : previousFingerprints.entrySet()) {
            String entryName = previousEntry.getKey();
            if (entryName.startsWith(JAR_CONTAINER_PREFIX) || entryName.startsWith("dir:")) {
                continue;
            }
            if (!currentEntryNames.contains(entryName)) {
                changedEntries.put(entryName, Status.REMOVED);
                if (entryName.endsWith(".class")) {
                    changedClassEntries.add(entryName);
                }
            }
        }
        return changedEntries;
    }

    private static Map<File, Status> detectDirectoryChanges(File dir,
                                                            Map<String, FileFingerprint> previousFingerprints,
                                                            Map<String, FileFingerprint> nextFingerprints,
                                                            Set<String> changedClassEntries) throws IOException {
        Map<File, Status> changedFiles = new HashMap<>();
        URI base = dir.toURI();
        Set<String> currentEntryNames = new HashSet<>();
        for (File file : Files.fileTraverser().depthFirstPreOrder(dir)) {
            if (!file.isFile() || !file.getName().endsWith(".class")) {
                continue;
            }
            byte[] data = Files.toByteArray(file);
            String entryName = base.relativize(file.toURI()).toString();
            String dirKey = directoryKey(file);
            currentEntryNames.add(entryName);
            FileFingerprint current = FileFingerprint.ofBytes(data);
            nextFingerprints.put(entryName, current);
            nextFingerprints.put(dirKey, current);
            FileFingerprint previous = previousFingerprints.get(entryName);
            if (previous == null) {
                previous = previousFingerprints.get(dirKey);
            }
            if (previous == null) {
                changedFiles.put(file, Status.ADDED);
                changedClassEntries.add(entryName);
            } else if (!previous.equals(current)) {
                changedFiles.put(file, Status.CHANGED);
                changedClassEntries.add(entryName);
            }
        }

        String dirPathPrefix = dir.getAbsolutePath();
        for (Map.Entry<String, FileFingerprint> entry : previousFingerprints.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("dir:") || !key.substring(4).startsWith(dirPathPrefix)) {
                continue;
            }
            File previousFile = new File(key.substring(4));
            String entryName = base.relativize(previousFile.toURI()).toString();
            if (!previousFile.exists()) {
                changedFiles.put(previousFile, Status.REMOVED);
                changedClassEntries.add(entryName);
            }
        }
        return changedFiles;
    }

    private static void collectJarFingerprints(File jar,
                                               Map<String, FileFingerprint> nextFingerprints,
                                               Set<String> changedClassEntries,
                                               boolean markAllChanged) throws IOException {
        nextFingerprints.put(jarContainerKey(jar), FileFingerprint.ofFile(jar));
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = ByteStreams.toByteArray(zis);
                String entryName = entry.getName();
                nextFingerprints.put(entryName, FileFingerprint.ofBytes(data));
                if (markAllChanged && entryName.endsWith(".class")) {
                    changedClassEntries.add(entryName);
                }
            }
        }
    }

    private static void collectDirectoryFingerprints(File dir,
                                                     Map<String, FileFingerprint> nextFingerprints,
                                                     Set<String> changedClassEntries,
                                                     boolean markAllChanged) throws IOException {
        URI base = dir.toURI();
        for (File file : Files.fileTraverser().depthFirstPreOrder(dir)) {
            if (!file.isFile() || !file.getName().endsWith(".class")) {
                continue;
            }
            byte[] data = Files.toByteArray(file);
            String entryName = base.relativize(file.toURI()).toString();
            nextFingerprints.put(entryName, FileFingerprint.ofBytes(data));
            nextFingerprints.put(directoryKey(file), FileFingerprint.ofBytes(data));
            if (markAllChanged) {
                changedClassEntries.add(entryName);
            }
        }
    }

    static String jarContainerKey(File jar) {
        return JAR_CONTAINER_PREFIX + jar.getAbsolutePath();
    }

    static String directoryKey(File classFile) {
        return "dir:" + classFile.getAbsolutePath();
    }
}
