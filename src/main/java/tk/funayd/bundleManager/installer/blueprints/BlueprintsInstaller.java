package tk.funayd.bundleManager.installer.blueprints;

import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.AbstractDirectoryInstaller;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.util.List;

public final class BlueprintsInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "Blueprints";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        // Blueprints bundle thato thuc te chi can giu nguyen cay thu muc model.
        return sourceSegments.size() >= 2;
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        return noMutations();
    }
}
