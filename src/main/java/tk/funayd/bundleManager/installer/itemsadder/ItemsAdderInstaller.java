package tk.funayd.bundleManager.installer.itemsadder;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.AbstractPluginInstaller;
import tk.funayd.bundleManager.installer.BundleFileReader;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ItemsAdderInstaller extends AbstractPluginInstaller {

    @Override
    public String getPluginKey() {
        return "ItemsAdder";
    }

    @Override
    public Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException {
        if (isIgnoredDataFile(relativePath)) {
            return Optional.empty();
        }

        List<String> segments = pathSegments(relativePath);
        String normalizedPath = normalizeContentPath(relativePath, segments);
        if (normalizedPath == null) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedBundleFile(relativePath, pluginPath(normalizedPath)));
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        LinkedHashSet<String> contentFolders = new LinkedHashSet<>();
        for (ResolvedBundleFile installedFile : installedFiles) {
            List<String> segments;
            try {
                segments = pathSegments(installedFile.getSourceRelativePath());
            } catch (BundleException ex) {
                continue;
            }

            String contentFolder = extractContentFolder(segments);
            if (contentFolder != null) {
                contentFolders.add(contentFolder);
            }
        }

        // Support both the new and legacy ItemsAdder config keys.
        ArrayList<BundleRecord.ConfigMutation> mutations = new ArrayList<>(contentFolders.size());
        for (String contentFolder : contentFolders) {
            mutations.add(appendStringListMutation(
                    "plugins/ItemsAdder/config.yml",
                    "contents-folders-priorities||resource-pack.zip.contents-folders-priority",
                    contentFolder
            ));
        }
        return mutations;
    }

    @Override
    public List<BundleInstallIdentity> collectIncomingIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        return contentFolderIdentities(plannedFiles);
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path contentsDirectory = serverRoot.resolve("plugins/ItemsAdder/contents");
        if (!Files.isDirectory(contentsDirectory)) {
            return List.of();
        }

        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        try (var paths = Files.list(contentsDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                identities.add(new BundleInstallIdentity(
                        "content folder",
                        path.getFileName().toString(),
                        "plugins/ItemsAdder/contents/" + path.getFileName()
                ));
            }
        } catch (Exception ex) {
            throw new BundleException("Failed to scan existing ItemsAdder content folders.", ex);
        }
        return identities;
    }

    private List<BundleInstallIdentity> contentFolderIdentities(List<ResolvedBundleFile> installedFiles) throws BundleException {
        LinkedHashSet<String> contentFolders = new LinkedHashSet<>();
        for (ResolvedBundleFile installedFile : installedFiles) {
            List<String> segments = pathSegments(installedFile.getSourceRelativePath());
            String contentFolder = extractContentFolder(segments);
            if (contentFolder != null) {
                contentFolders.add(contentFolder);
            }
        }

        ArrayList<BundleInstallIdentity> identities = new ArrayList<>(contentFolders.size());
        for (String contentFolder : contentFolders) {
            identities.add(new BundleInstallIdentity(
                    "content folder",
                    contentFolder,
                    "contents/" + contentFolder
            ));
        }
        return identities;
    }

    private String normalizeContentPath(String relativePath, List<String> segments) {
        if (segments.size() >= 3 && "contents".equals(segments.get(0))) {
            return relativePath;
        }
        if (segments.size() >= 2) {
            return "contents/" + relativePath;
        }
        return null;
    }

    private String extractContentFolder(List<String> segments) {
        if (segments.size() >= 3 && "contents".equals(segments.get(0))) {
            return segments.get(1);
        }
        if (segments.size() >= 2) {
            return segments.get(0);
        }
        return null;
    }
}
