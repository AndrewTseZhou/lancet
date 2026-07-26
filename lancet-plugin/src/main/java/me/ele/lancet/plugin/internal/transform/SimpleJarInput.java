package me.ele.lancet.plugin.internal.transform;

import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class SimpleJarInput implements JarInput {

    private final File file;
    private final Status status;
    private final String name;
    private final Map<String, Status> changedEntries;

    public SimpleJarInput(File file, Status status) {
        this(file, status, Collections.emptyMap());
    }

    public SimpleJarInput(File file, Status status, Map<String, Status> changedEntries) {
        this.file = file;
        this.status = status;
        this.name = file.getName();
        this.changedEntries = changedEntries == null ? Collections.emptyMap() : changedEntries;
    }

    public Map<String, Status> getChangedEntries() {
        return Collections.unmodifiableMap(changedEntries);
    }

    public boolean hasEntryLevelChanges() {
        return !changedEntries.isEmpty();
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public Set<QualifiedContent.ContentType> getContentTypes() {
        return Collections.emptySet();
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return Collections.emptySet();
    }
}
