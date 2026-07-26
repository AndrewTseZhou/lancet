package me.ele.lancet.plugin.internal.context;

import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.google.common.io.ByteStreams;

import org.apache.commons.io.IOUtils;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import me.ele.lancet.plugin.internal.transform.SimpleJarInput;

/**
 * Reads classes from jar inputs.
 */
public class JarContentProvider extends TargetedQualifiedContentProvider {

    @Override
    public void forEach(QualifiedContent content, ClassFetcher processor) throws IOException {
        forActualInput((JarInput) content, processor);
    }

    private void forActualInput(JarInput jarInput, ClassFetcher processor) throws IOException {
        if (processor.onStart(jarInput)) {
            Map<String, Status> changedEntries = jarInput instanceof SimpleJarInput
                    ? ((SimpleJarInput) jarInput).getChangedEntries()
                    : null;
            boolean entryLevelIncremental = changedEntries != null && !changedEntries.isEmpty();
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jarInput.getFile())));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                Status status = resolveEntryStatus(jarInput, entryName, changedEntries, entryLevelIncremental);
                if (status == Status.NOTCHANGED || status == Status.REMOVED) {
                    continue;
                }
                byte[] data = ByteStreams.toByteArray(zis);
                processor.onClassFetch(jarInput, status, entryName, data);
            }
            IOUtils.closeQuietly(zis);
        }
        processor.onComplete(jarInput);
    }

    private Status resolveEntryStatus(JarInput jarInput,
                                      String entryName,
                                      Map<String, Status> changedEntries,
                                      boolean entryLevelIncremental) {
        if (!entryLevelIncremental) {
            return jarInput.getStatus();
        }
        Status status = changedEntries.get(entryName);
        if (status != null) {
            return status;
        }
        return Status.NOTCHANGED;
    }

    @Override
    public boolean accepted(QualifiedContent qualifiedContent) {
        return qualifiedContent instanceof JarInput;
    }
}
