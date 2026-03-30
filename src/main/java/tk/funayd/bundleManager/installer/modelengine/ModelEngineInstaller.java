package tk.funayd.bundleManager.installer.modelengine;

import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.AbstractDirectoryInstaller;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.util.List;
import java.util.Locale;

public final class ModelEngineInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "ModelEngine";
    }

    @Override
    public boolean canRequestOverwrite(ResolvedBundleFile bundleFile) {
        return true;
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        // ModelEngine trong bundle thato thuc te nam duoi blueprints/**.
        return sourceSegments.size() >= 2
                && "blueprints".equals(sourceSegments.get(0).toLowerCase(Locale.ROOT));
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        return noMutations();
    }
}
