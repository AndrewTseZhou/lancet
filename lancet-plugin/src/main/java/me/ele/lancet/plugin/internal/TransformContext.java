package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import me.ele.lancet.weaver.internal.graph.Graph;

/**
 * Created by gengwanpeng on 17/4/26.
 *
 * A data sets collect all jar info and pre-analysis result.
 *
 */
public class TransformContext {

    private final boolean incremental;
    private final File outputFile;
    private final Collection<JarInput> allJars;
    private final Collection<JarInput> addedJars;
    private final Collection<JarInput> removedJars;
    private final Collection<JarInput> changedJars;
    private final Collection<JarInput> notChangedJars;
    private final Collection<DirectoryInput> allDirs;

    private final GlobalContext global;
    private List<String> hookClasses;
    private Graph graph;
    private Set<String> changedClassEntries = Collections.emptySet();

    public TransformContext(Collection<JarInput> jarInputs, Collection<DirectoryInput> directoryInputs, boolean incremental,
                            File outputFile, GlobalContext global) {
        this.global = global;
        this.incremental = incremental;
        this.outputFile = outputFile;

        this.allJars = new ArrayList<>(jarInputs);
        this.addedJars = new ArrayList<>(jarInputs.size());
        this.changedJars = new ArrayList<>(jarInputs.size());
        this.removedJars = new ArrayList<>(jarInputs.size());
        this.notChangedJars = new ArrayList<>(jarInputs.size());
        this.allDirs = new ArrayList<>(directoryInputs);

        if (incremental) {
            jarInputs.forEach(j -> {
                switch (j.getStatus()) {
                    case ADDED:
                        addedJars.add(j);
                        break;
                    case REMOVED:
                        removedJars.add(j);
                        break;
                    case CHANGED:
                        changedJars.add(j);
                        break;
                    case NOTCHANGED:
                        notChangedJars.add(j);
                        break;
                    default:
                        break;
                }
            });
        }
    }


    public boolean isIncremental() {
        return incremental;
    }

    public Collection<JarInput> getAllJars() {
        return Collections.unmodifiableCollection(allJars);
    }

    public Collection<DirectoryInput> getAllDirs() {
        return Collections.unmodifiableCollection(allDirs);
    }

    public Collection<JarInput> getAddedJars() {
        return Collections.unmodifiableCollection(addedJars);
    }

    public Collection<JarInput> getChangedJars() {
        return Collections.unmodifiableCollection(changedJars);
    }

    public Collection<JarInput> getRemovedJars() {
        return Collections.unmodifiableCollection(removedJars);
    }

    public Collection<JarInput> getNotChangedJars() {
        return Collections.unmodifiableCollection(notChangedJars);
    }

    public File getOutputFile() {
        return outputFile;
    }

    public void clear() throws IOException {
        if (outputFile != null) {
            Files.deleteIfExists(outputFile.toPath());
        }
    }

    public GlobalContext getGlobal() {
        return global;
    }

    public void setHookClasses(List<String> hookClasses) {
        this.hookClasses = hookClasses;
    }

    public List<String> getHookClasses() {
        return hookClasses;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public Set<String> getChangedClassEntries() {
        return changedClassEntries;
    }

    public void setChangedClassEntries(Set<String> changedClassEntries) {
        this.changedClassEntries = changedClassEntries == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(changedClassEntries);
    }

    @Override
    public String toString() {
        return "TransformContext{" +
                "allJars=" + allJars +
                ", addedJars=" + addedJars +
                ", removedJars=" + removedJars +
                ", changedJars=" + changedJars +
                ", allDirs=" + allDirs +
                '}';
    }
}
