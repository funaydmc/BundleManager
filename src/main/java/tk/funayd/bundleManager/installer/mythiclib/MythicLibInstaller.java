package tk.funayd.bundleManager.installer.mythiclib;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.AbstractDirectoryInstaller;
import tk.funayd.bundleManager.installer.BundleFileReader;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MythicLibInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "MythicLib";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        // MythicLib usually loads registry files from child directories, so root configs are ignored.
        return sourceSegments.size() >= 2;
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        return noMutations();
    }

    @Override
    public List<BundleInstallIdentity> collectIncomingIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        return collectYamlRootIdentities("mythiclib id", plannedFiles, fileReader);
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path pluginRoot = serverRoot.resolve("plugins/MythicLib");
        if (!Files.isDirectory(pluginRoot)) {
            return List.of();
        }

        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        try (var paths = Files.walk(pluginRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!isYamlFileName(path.getFileName().toString())) {
                    continue;
                }

                String relativePath = pluginRoot.relativize(path).toString().replace('\\', '/');
                identities.addAll(rootKeyIdentities(
                        "mythiclib id",
                        loadYaml(path).getKeys(false),
                        "plugins/MythicLib/" + relativePath
                ));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing MythicLib ids.", ex);
        }
        return identities;
    }
}
