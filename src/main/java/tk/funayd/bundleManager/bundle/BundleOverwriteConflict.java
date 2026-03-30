package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleOverwriteConflict {

    private final String id;
    private final String bundleId;
    private final String sourceZipName;
    private final String packageKey;
    private final List<String> targetPaths;
    private final long createdAtEpochMillis;

    public BundleOverwriteConflict(
            String id,
            String bundleId,
            String sourceZipName,
            String packageKey,
            List<String> targetPaths,
            long createdAtEpochMillis
    ) {
        this.id = id;
        this.bundleId = bundleId;
        this.sourceZipName = sourceZipName;
        this.packageKey = packageKey;
        this.targetPaths = List.copyOf(targetPaths);
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getId() {
        return id;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public String getPackageKey() {
        return packageKey;
    }

    public List<String> getTargetPaths() {
        return Collections.unmodifiableList(targetPaths);
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }
}
