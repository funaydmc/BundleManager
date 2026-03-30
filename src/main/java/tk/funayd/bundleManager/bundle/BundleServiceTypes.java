package tk.funayd.bundleManager.bundle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record BundleArchiveDescriptor(
        String bundleId,
        String sourceZipName,
        String sourceSha1,
        List<String> installablePackages,
        List<String> allPackages,
        List<BundleVariantGroup> variantGroups,
        List<VariantChoiceGroup> variantChoiceGroups,
        List<String> scanWarnings
) {
}

record ArchivePackageInfo(
        List<BundlePackageDescriptor> packageDescriptors,
        List<String> installablePackages,
        List<String> allPackages,
        List<BundleVariantGroup> variantGroups,
        List<VariantChoiceGroup> variantChoiceGroups
) {
}

record BundlePackageDescriptor(
        String packageKey,
        String pluginKey,
        String rootPath,
        boolean supported,
        List<BundleArchiveEntry> entries,
        List<String> rawVariantParts,
        List<String> normalizedVariantParts
) {
}

record PluginFolderInfo(
        String pluginName,
        String variantName
) {
}

record BundleVariantGroup(
        String pluginKey,
        List<BundleVariantOption> options
) {
}

record BundleVariantOption(
        String packageKey,
        String displayName
) {
}

record VariantPromptOption(
        int index,
        String bundleId,
        String sourceZipName,
        String title,
        String displayName,
        Map<String, String> selections
) {
}

record VariantChoiceGroup(
        String title,
        List<VariantChoiceOption> options
) {
}

record VariantChoiceOption(
        String displayName,
        Map<String, String> selections
) {
}

record VariantSelectionDecision(
        BundlePreference preference,
        List<String> targetPackages,
        List<String> messages
) {
}

record KnownBundle(
        String bundleId,
        String sourceZipName,
        BundleArchiveDescriptor archiveDescriptor,
        List<String> installablePackages,
        List<String> allKnownPackages
) {
}

record ConfigMutationResult(boolean applied, String warning) {
}

final class BundleVariantAggregate {
    final String displayName;
    final LinkedHashMap<String, String> selections;

    BundleVariantAggregate(String displayName) {
        this.displayName = displayName;
        this.selections = new LinkedHashMap<>();
    }
}
