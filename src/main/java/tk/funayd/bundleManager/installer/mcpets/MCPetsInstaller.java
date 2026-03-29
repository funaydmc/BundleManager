package tk.funayd.bundleManager.installer.mcpets;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import org.bukkit.configuration.file.YamlConfiguration;
import tk.funayd.bundleManager.installer.AbstractDirectoryInstaller;
import tk.funayd.bundleManager.installer.BundleFileReader;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MCPetsInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "MCPets";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        return sourceSegments.size() >= 2
                && "pets".equals(sourceSegments.get(0).toLowerCase(Locale.ROOT));
    }

    @Override
    protected boolean shouldPrefixLeafFile(List<String> sourceSegments) {
        // Id pet nam trong truong Id:, khong duoc doi.
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
            identities.addAll(readPetIdentities(
                    plannedFile.getSourceRelativePath(),
                    loadYaml(fileReader.readFile(plannedFile), plannedFile.getSourceEntryName())
            ));
        }
        return identities;
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path pluginRoot = serverRoot.resolve("plugins/MCPets");
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
                identities.addAll(readPetIdentities("plugins/MCPets/" + relativePath, loadYaml(path)));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing MCPets ids.", ex);
        }
        return identities;
    }

    private List<BundleInstallIdentity> readPetIdentities(String source, YamlConfiguration yaml) {
        String petId = yaml.getString("Id");
        if (petId == null || petId.isBlank()) {
            return List.of();
        }
        return List.of(new BundleInstallIdentity("pet id", petId, source));
    }
}
