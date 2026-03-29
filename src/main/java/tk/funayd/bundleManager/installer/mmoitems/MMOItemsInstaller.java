package tk.funayd.bundleManager.installer.mmoitems;

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

public final class MMOItemsInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "MMOItems";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        // MMOItems bundle noi dung thuong la file trong cac category con nhu item/ va skill/.
        return sourceSegments.size() >= 2;
    }

    @Override
    protected boolean shouldPrefixLeafFile(List<String> sourceSegments) {
        // Giu nguyen id item/skill trong file, chi doi ten file de tranh trung path.
        return isYamlFileName(sourceSegments.get(sourceSegments.size() - 1));
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
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            identities.addAll(readIdentities(
                    plannedFile.getSourceRelativePath(),
                    loadYaml(fileReader.readFile(plannedFile), plannedFile.getSourceEntryName())
            ));
        }
        return identities;
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path pluginRoot = serverRoot.resolve("plugins/MMOItems");
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
                identities.addAll(readIdentities("plugins/MMOItems/" + relativePath, loadYaml(path)));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing MMOItems ids.", ex);
        }
        return identities;
    }

    private List<BundleInstallIdentity> readIdentities(String source, org.bukkit.configuration.file.YamlConfiguration yaml)
            throws BundleException {
        String type = sourceType(source);
        if ("skill id".equals(type)) {
            return List.of(new BundleInstallIdentity(type, fileBaseName(source), source));
        }
        return rootKeyIdentities(type, yaml.getKeys(false), source);
    }

    private String sourceType(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        if (segments.isEmpty()) {
            return "mmoitems id";
        }

        int rootIndex = 0;
        if (segments.size() >= 3
                && "plugins".equalsIgnoreCase(segments.get(0))
                && "mmoitems".equalsIgnoreCase(segments.get(1))) {
            rootIndex = 2;
        }

        return segments.size() <= rootIndex ? "mmoitems id" : segments.get(rootIndex) + " id";
    }
}
