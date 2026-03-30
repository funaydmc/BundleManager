package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleLoadReport {

    private final int installedPackageCount;
    private final int installedBundleCount;
    private final List<MissingPluginRequirement> missingPlugins;
    private final List<String> warnings;
    private final List<String> errors;

    public BundleLoadReport(
            int installedPackageCount,
            int installedBundleCount,
            List<MissingPluginRequirement> missingPlugins,
            List<String> warnings,
            List<String> errors
    ) {
        this.installedPackageCount = installedPackageCount;
        this.installedBundleCount = installedBundleCount;
        this.missingPlugins = List.copyOf(missingPlugins);
        this.warnings = List.copyOf(warnings);
        this.errors = List.copyOf(errors);
    }

    public int getInstalledPackageCount() {
        return installedPackageCount;
    }

    public int getInstalledBundleCount() {
        return installedBundleCount;
    }

    public List<MissingPluginRequirement> getMissingPlugins() {
        return Collections.unmodifiableList(missingPlugins);
    }

    public boolean hasMissingPlugins() {
        return !missingPlugins.isEmpty();
    }

    public String getMissingPluginHeader() {
        int missingPluginCount = missingPlugins.size();
        return "There " + (missingPluginCount == 1 ? "is " : "are ")
                + missingPluginCount + " missing " + (missingPluginCount == 1 ? "plugin:" : "plugins:");
    }

    public List<String> getMissingPluginLines() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(missingPlugins.size());
        for (MissingPluginRequirement requirement : missingPlugins) {
            lines.add("- " + requirement.pluginName()
                    + " (required by bundle " + String.join(", ", requirement.bundleIds()) + ")");
        }
        return lines;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
