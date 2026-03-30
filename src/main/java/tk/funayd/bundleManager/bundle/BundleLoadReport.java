package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleLoadReport {

    private final int installedPackageCount;
    private final int installedBundleCount;
    private final List<String> warnings;
    private final List<String> errors;

    public BundleLoadReport(
            int installedPackageCount,
            int installedBundleCount,
            List<String> warnings,
            List<String> errors
    ) {
        this.installedPackageCount = installedPackageCount;
        this.installedBundleCount = installedBundleCount;
        this.warnings = List.copyOf(warnings);
        this.errors = List.copyOf(errors);
    }

    public int getInstalledPackageCount() {
        return installedPackageCount;
    }

    public int getInstalledBundleCount() {
        return installedBundleCount;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
