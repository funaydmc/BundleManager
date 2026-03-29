package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleSummary {

    private final String bundleId;
    private final String sourceZipName;
    private final long installedAtEpochMillis;
    private final List<String> packageKeys;
    private final int installedFileCount;

    public BundleSummary(
            String bundleId,
            String sourceZipName,
            long installedAtEpochMillis,
            List<String> packageKeys,
            int installedFileCount
    ) {
        this.bundleId = bundleId;
        this.sourceZipName = sourceZipName;
        this.installedAtEpochMillis = installedAtEpochMillis;
        this.packageKeys = List.copyOf(packageKeys);
        this.installedFileCount = installedFileCount;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public long getInstalledAtEpochMillis() {
        return installedAtEpochMillis;
    }

    public List<String> getPackageKeys() {
        return Collections.unmodifiableList(packageKeys);
    }

    public int getInstalledFileCount() {
        return installedFileCount;
    }

    public String getBundleShortId() {
        return bundleId;
    }
}
