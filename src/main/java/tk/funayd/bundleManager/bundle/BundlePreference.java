package tk.funayd.bundleManager.bundle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BundlePreference {

    private final String bundleId;
    private final String sourceZipName;
    private final String sourceSha1;
    private final boolean bundleDisabled;
    private final List<String> disabledPackages;
    private final String selectedBundleVariant;
    private final Map<String, String> selectedPackages;

    public BundlePreference(
            String bundleId,
            String sourceZipName,
            String sourceSha1,
            boolean bundleDisabled,
            List<String> disabledPackages,
            Map<String, String> selectedPackages
    ) {
        this(bundleId, sourceZipName, sourceSha1, bundleDisabled, disabledPackages, null, selectedPackages);
    }

    public BundlePreference(
            String bundleId,
            String sourceZipName,
            String sourceSha1,
            boolean bundleDisabled,
            List<String> disabledPackages,
            String selectedBundleVariant,
            Map<String, String> selectedPackages
    ) {
        this.bundleId = bundleId;
        this.sourceZipName = sourceZipName;
        this.sourceSha1 = sourceSha1;
        this.bundleDisabled = bundleDisabled;
        this.disabledPackages = List.copyOf(disabledPackages);
        this.selectedBundleVariant = selectedBundleVariant;
        this.selectedPackages = Map.copyOf(new LinkedHashMap<>(selectedPackages));
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public String getSourceSha1() {
        return sourceSha1;
    }

    public boolean isBundleDisabled() {
        return bundleDisabled;
    }

    public List<String> getDisabledPackages() {
        return disabledPackages;
    }

    public String getSelectedBundleVariant() {
        return selectedBundleVariant;
    }

    public Map<String, String> getSelectedPackages() {
        return selectedPackages;
    }
}
