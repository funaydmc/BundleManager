package tk.funayd.bundleManager.installer.oraxen;

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
import java.util.Locale;

public final class OraxenInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "Oraxen";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        if (sourceSegments.size() < 2) {
            return false;
        }

        String rootDirectory = sourceSegments.get(0).toLowerCase(Locale.ROOT);
        // Chi nhan cac thu muc content chinh theo docs, bo qua settings/mechanics o root.
        return "items".equals(rootDirectory)
                || "pack".equals(rootDirectory)
                || "recipes".equals(rootDirectory)
                || "glyphs".equals(rootDirectory);
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
        return collectKnownYamlIdentities(plannedFiles, fileReader);
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        return collectKnownYamlIdentities(serverRoot.resolve("plugins/Oraxen"));
    }

    private List<BundleInstallIdentity> collectKnownYamlIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            if (!shouldReadRootYamlIds(plannedFile.getSourceRelativePath())) {
                continue;
            }

            identities.addAll(rootKeyIdentities(
                    identityType(plannedFile.getSourceRelativePath()),
                    loadYaml(fileReader.readFile(plannedFile), plannedFile.getSourceEntryName()).getKeys(false),
                    plannedFile.getSourceRelativePath()
            ));
        }
        return identities;
    }

    private List<BundleInstallIdentity> collectKnownYamlIdentities(Path pluginRoot) throws BundleException {
        if (!Files.isDirectory(pluginRoot)) {
            return List.of();
        }

        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        try (var paths = Files.walk(pluginRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relativePath = pluginRoot.relativize(path).toString().replace('\\', '/');
                if (!shouldReadRootYamlIds(relativePath)) {
                    continue;
                }

                identities.addAll(rootKeyIdentities(
                        identityType(relativePath),
                        loadYaml(path).getKeys(false),
                        "plugins/Oraxen/" + relativePath
                ));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing Oraxen ids.", ex);
        }
        return identities;
    }

    private boolean shouldReadRootYamlIds(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        return segments.size() >= 2
                && isYamlFileName(segments.get(segments.size() - 1))
                && ("items".equalsIgnoreCase(segments.get(0)) || "glyphs".equalsIgnoreCase(segments.get(0)));
    }

    private String identityType(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        if (segments.isEmpty()) {
            return "oraxen id";
        }
        return switch (segments.get(0).toLowerCase(Locale.ROOT)) {
            case "items" -> "oraxen item id";
            case "glyphs" -> "oraxen glyph id";
            default -> "oraxen id";
        };
    }
}
