package tk.funayd.bundleManager.installer.nexo;

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

public final class NexoInstaller extends AbstractDirectoryInstaller {

    @Override
    public String getPluginKey() {
        return "Nexo";
    }

    @Override
    protected boolean shouldInstallPath(List<String> sourceSegments) {
        if (sourceSegments.size() < 2) {
            return false;
        }

        String rootDirectory = sourceSegments.get(0).toLowerCase(Locale.ROOT);
        // Nexo docs tap trung vao items, glyphs, dialogs va pack.
        return "items".equals(rootDirectory)
                || "glyphs".equals(rootDirectory)
                || "dialogs".equals(rootDirectory)
                || "pack".equals(rootDirectory);
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
        return collectKnownYamlIdentities(serverRoot.resolve("plugins/Nexo"));
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
                        "plugins/Nexo/" + relativePath
                ));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing Nexo ids.", ex);
        }
        return identities;
    }

    private boolean shouldReadRootYamlIds(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        return segments.size() >= 2
                && isYamlFileName(segments.get(segments.size() - 1))
                && ("items".equalsIgnoreCase(segments.get(0))
                || "glyphs".equalsIgnoreCase(segments.get(0))
                || "dialogs".equalsIgnoreCase(segments.get(0)));
    }

    private String identityType(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        if (segments.isEmpty()) {
            return "nexo id";
        }
        return switch (segments.get(0).toLowerCase(Locale.ROOT)) {
            case "items" -> "nexo item id";
            case "glyphs" -> "nexo glyph id";
            case "dialogs" -> "nexo dialog id";
            default -> "nexo id";
        };
    }
}
