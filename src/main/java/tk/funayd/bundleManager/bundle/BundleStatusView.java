package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleStatusView {

    private final String bundleId;
    private final String sourceZipName;
    private final List<BundlePackageView> packageViews;
    private final BundleOverallState overallState;

    public BundleStatusView(
            String bundleId,
            String sourceZipName,
            List<BundlePackageView> packageViews,
            BundleOverallState overallState
    ) {
        this.bundleId = bundleId;
        this.sourceZipName = sourceZipName;
        this.packageViews = List.copyOf(packageViews);
        this.overallState = overallState;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public List<BundlePackageView> getPackageViews() {
        return Collections.unmodifiableList(packageViews);
    }

    public BundleOverallState getOverallState() {
        return overallState;
    }

    public String getBundleShortId() {
        return bundleId;
    }

    public String getBundleName() {
        return PathUtils.baseName(sourceZipName);
    }
}
