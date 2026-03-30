package tk.funayd.bundleManager.installer.deluxemenus;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.bundle.PathUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import tk.funayd.bundleManager.installer.AbstractPluginInstaller;
import tk.funayd.bundleManager.installer.BundleFileReader;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DeluxeMenusInstaller extends AbstractPluginInstaller {

    private static final String GUI_MENUS_DIRECTORY = "gui_menus";
    private static final String GUI_MENUS_PATH_PREFIX = "plugins/DeluxeMenus/gui_menus/";

    @Override
    public String getPluginKey() {
        return "DeluxeMenus";
    }

    @Override
    public Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException {
        if (isIgnoredDataFile(relativePath)) {
            return Optional.empty();
        }

        List<String> sourceSegments = pathSegments(relativePath);
        if (sourceSegments.size() == 1 && "config.yml".equalsIgnoreCase(sourceSegments.get(0))) {
            return Optional.empty();
        }

        List<String> menuSegments = normalizeMenuSegments(sourceSegments);
        if (menuSegments.isEmpty()) {
            return Optional.empty();
        }

        String originalFileName = menuSegments.get(menuSegments.size() - 1);
        if (!originalFileName.toLowerCase(Locale.ROOT).endsWith(".yml")) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedBundleFile(
                relativePath,
                pluginPath(GUI_MENUS_DIRECTORY + "/" + String.join("/", menuSegments))
        ));
    }

    @Override
    public Optional<ResolvedBundleFile> resolveRenameOnConflict(
            ResolvedBundleFile bundleFile,
            String bundleIdShort
    ) throws BundleException {
        // DeluxeMenus dang ky menu qua gui_menus.<id>.file, nen file menu co the doi ten neu bi trung path.
        return Optional.of(renameTargetFileOnConflict(bundleFile, bundleIdShort));
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        ArrayList<BundleRecord.ConfigMutation> mutations = new ArrayList<>(installedFiles.size());
        for (ResolvedBundleFile installedFile : installedFiles) {
            String menuName;
            String targetFile;
            try {
                // Menu id duoc dung trong /dm open va openguimenu, nen phai giu nguyen.
                menuName = PathUtils.baseName(fileName(installedFile.getSourceRelativePath()));
                targetFile = targetFileValue(installedFile.getTargetRelativePath());
            } catch (BundleException ex) {
                continue;
            }

            mutations.add(registerSectionFileMutation(
                    "plugins/DeluxeMenus/config.yml",
                    "gui_menus." + menuName,
                    targetFile
            ));
        }
        return mutations;
    }

    @Override
    public List<BundleInstallIdentity> collectIncomingIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>(plannedFiles.size());
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            identities.add(new BundleInstallIdentity(
                    "menu id",
                    PathUtils.baseName(fileName(plannedFile.getSourceRelativePath())),
                    plannedFile.getSourceRelativePath()
            ));
        }
        return identities;
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path configPath = serverRoot.resolve("plugins/DeluxeMenus/config.yml");
        if (!Files.exists(configPath)) {
            return List.of();
        }

        YamlConfiguration config = loadYaml(configPath);
        ConfigurationSection guiMenus = config.getConfigurationSection("gui_menus");
        if (guiMenus == null) {
            return List.of();
        }

        return rootKeyIdentities("menu id", guiMenus.getKeys(false), "plugins/DeluxeMenus/config.yml");
    }

    private List<String> normalizeMenuSegments(List<String> sourceSegments) {
        if (!sourceSegments.isEmpty() && GUI_MENUS_DIRECTORY.equalsIgnoreCase(sourceSegments.get(0))) {
            return new ArrayList<>(sourceSegments.subList(1, sourceSegments.size()));
        }
        return new ArrayList<>(sourceSegments);
    }

    private String targetFileValue(String targetRelativePath) throws BundleException {
        String normalizedTargetPath = PathUtils.normalizeRelativePath(targetRelativePath);
        if (!normalizedTargetPath.startsWith(GUI_MENUS_PATH_PREFIX)) {
            throw new BundleException("Unexpected DeluxeMenus target path: " + targetRelativePath);
        }
        return normalizedTargetPath.substring(GUI_MENUS_PATH_PREFIX.length());
    }
}
