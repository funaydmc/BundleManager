package tk.funayd.bundleManager.installer.resourcepack;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.bundle.PathUtils;
import tk.funayd.bundleManager.installer.AbstractPluginInstaller;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.util.List;
import java.util.Optional;

public final class ResourcePackInstaller extends AbstractPluginInstaller {

    @Override
    public String getPluginKey() {
        return "ResourcePack";
    }

    @Override
    public Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException {
        if (isIgnoredDataFile(relativePath)) {
            return Optional.empty();
        }

        // Resource packs are copied into BundleManager's dedicated pack directory.
        String targetPath = PathUtils.normalizeRelativePath("plugins/BundleManager/pack/" + bundleIdShort + "/" + relativePath);
        return Optional.of(new ResolvedBundleFile(relativePath, targetPath));
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        return noMutations();
    }
}
