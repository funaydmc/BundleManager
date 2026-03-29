package tk.funayd.bundleManager.bundle;

public final class BundlePackageView {

    private final String packageKey;
    private final String displayName;
    private final BundlePackageState state;

    public BundlePackageView(String packageKey, String displayName, BundlePackageState state) {
        this.packageKey = packageKey;
        this.displayName = displayName;
        this.state = state;
    }

    public String getPackageKey() {
        return packageKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BundlePackageState getState() {
        return state;
    }
}
