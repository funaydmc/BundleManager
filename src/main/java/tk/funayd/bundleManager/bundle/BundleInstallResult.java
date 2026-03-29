package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleInstallResult {

    private final BundleRecord record;
    private final List<String> warnings;

    public BundleInstallResult(BundleRecord record, List<String> warnings) {
        this.record = record;
        this.warnings = List.copyOf(warnings);
    }

    public BundleRecord getRecord() {
        return record;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
}
