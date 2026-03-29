package tk.funayd.bundleManager.bundle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tk.funayd.bundleManager.support.TestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExternalBundleSmokeTest {

    private static final Path EXTERNAL_BUNDLES_DIRECTORY = Path.of("C:/Users/admin/Downloads/bundles");

    @TempDir
    Path tempDir;

    @Test
    void shouldReadAllExternalBundlesWithoutCrashing() throws Exception {
        assumeTrue(Files.isDirectory(EXTERNAL_BUNDLES_DIRECTORY), "External bundles directory is not available.");

        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        try (var paths = Files.list(EXTERNAL_BUNDLES_DIRECTORY)) {
            for (Path archivePath : paths.filter(this::isSupportedArchive).toList()) {
                Path copiedArchive = copyToIncomingFolder(service, archivePath);

                List<String> byFileName = service.listInstallablePackages(copiedArchive.getFileName().toString());
                List<String> byBaseName = service.listInstallablePackages(baseName(copiedArchive.getFileName().toString()));

                assertNotNull(byFileName, archivePath.toString());
                assertEquals(byFileName, byBaseName, archivePath.toString());
            }
        }
    }

    @Test
    void shouldInstallAndUninstallKnownExternalBundles() throws Exception {
        assumeTrue(Files.isDirectory(EXTERNAL_BUNDLES_DIRECTORY), "External bundles directory is not available.");

        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path deluxeConfig = serverRoot.resolve("plugins/DeluxeMenus/config.yml");
        Path itemsAdderConfig = serverRoot.resolve("plugins/ItemsAdder/config.yml");
        Files.createDirectories(deluxeConfig.getParent());
        Files.createDirectories(itemsAdderConfig.getParent());
        Files.writeString(deluxeConfig, "gui_menus: {}\n");
        Files.writeString(itemsAdderConfig, "contents-folders-priorities:\n  - vanilla\n");

        Path lostAssets = EXTERNAL_BUNDLES_DIRECTORY.resolve("LostAssets_ClassPack_04_Revenant (1).zip");
        assumeTrue(Files.exists(lostAssets), "LostAssets bundle is not available.");
        copyToIncomingFolder(service, lostAssets);

        List<String> lostAssetsPackages = service.listInstallablePackages(lostAssets.getFileName().toString());
        assertTrue(lostAssetsPackages.contains("ItemsAdder"));
        assertTrue(lostAssetsPackages.contains("MMOItems"));
        assertTrue(lostAssetsPackages.contains("ModelEngine"));
        assertTrue(lostAssetsPackages.contains("MythicLib"));
        assertTrue(lostAssetsPackages.contains("MythicMobs"));

        List<BundleInstallResult> itemsAdderResults = service.installBundle(lostAssets.getFileName().toString(), "ItemsAdder");
        assertEquals(1, itemsAdderResults.size());
        assertTrue(itemsAdderResults.get(0).getRecord().getInstalledFiles().size() > 0);
        assertTrue(Files.exists(serverRoot.resolve("plugins/ItemsAdder/contents/LostAssets_ClassPack_04_Revenant")));
        assertTrue(YamlConfiguration.loadConfiguration(itemsAdderConfig.toFile())
                .getStringList("contents-folders-priorities")
                .contains("LostAssets_ClassPack_04_Revenant"));

        service.uninstallBundle(lostAssets.getFileName().toString(), "ItemsAdder");
        assertFalse(Files.exists(serverRoot.resolve("plugins/ItemsAdder/contents/LostAssets_ClassPack_04_Revenant")));

        List<BundleInstallResult> mmoItemsResults = service.installBundle(lostAssets.getFileName().toString(), "MMOItems");
        assertEquals(1, mmoItemsResults.size());
        String lostAssetsShortId = mmoItemsResults.get(0).getRecord().getBundleShortId();
        assertTrue(Files.exists(serverRoot.resolve("plugins/MMOItems/item/" + lostAssetsShortId + "_sword.yml")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/MMOItems/skill/" + lostAssetsShortId + "_lostassets-revenant-lc.yml")));
        service.uninstallBundle(lostAssets.getFileName().toString(), "MMOItems");
        assertFalse(Files.exists(serverRoot.resolve("plugins/MMOItems/item/" + lostAssetsShortId + "_sword.yml")));

        List<BundleInstallResult> mythicLibResults = service.installBundle(lostAssets.getFileName().toString(), "MythicLib");
        assertEquals(1, mythicLibResults.size());
        assertTrue(Files.exists(serverRoot.resolve(
                "plugins/MythicLib/skill/" + mythicLibResults.get(0).getRecord().getBundleShortId()
                        + "_LostAssets_ClassPack_04_Revenant.yml"
        )));
        service.uninstallBundle(lostAssets.getFileName().toString(), "MythicLib");

        List<BundleInstallResult> modelEngineResults = service.installBundle(lostAssets.getFileName().toString(), "ModelEngine");
        assertEquals(1, modelEngineResults.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/lostassets_revenant.bbmodel")));
        service.uninstallBundle(lostAssets.getFileName().toString(), "ModelEngine");
        assertFalse(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/lostassets_revenant.bbmodel")));

        Path witches = EXTERNAL_BUNDLES_DIRECTORY.resolve("UPDATE WITCHES V1.zip");
        assumeTrue(Files.exists(witches), "UPDATE WITCHES bundle is not available.");
        copyToIncomingFolder(service, witches);

        List<String> witchesPackages = service.listInstallablePackages(witches.getFileName().toString());
        assertTrue(witchesPackages.contains("MCPets"));
        assertTrue(witchesPackages.contains("ModelEngine"));
        assertTrue(witchesPackages.contains("MythicMobs"));

        List<BundleInstallResult> mcPetsResults = service.installBundle(witches.getFileName().toString(), "MCPets");
        assertEquals(1, mcPetsResults.size());
        String witchesShortId = mcPetsResults.get(0).getRecord().getBundleShortId();
        assertTrue(Files.exists(serverRoot.resolve("plugins/MCPets/Pets/Pets_witches/" + witchesShortId + "_Witch_pet_Green.yml")));
        service.uninstallBundle(witches.getFileName().toString(), "MCPets");
        assertFalse(Files.exists(serverRoot.resolve("plugins/MCPets/Pets/Pets_witches/" + witchesShortId + "_Witch_pet_Green.yml")));

        List<BundleInstallResult> witchesModelEngineResults = service.installBundle(witches.getFileName().toString(), "ModelEngine");
        assertEquals(1, witchesModelEngineResults.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/gamitamodels_witches/Witch_pet_Green.bbmodel")));
        service.uninstallBundle(witches.getFileName().toString(), "ModelEngine");
        assertFalse(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/gamitamodels_witches/Witch_pet_Green.bbmodel")));

        List<BundleInstallResult> mythicResults = service.installBundle(witches.getFileName().toString(), "MythicMobs");
        assertEquals(1, mythicResults.size());
        assertTrue(mythicResults.get(0).getRecord().getInstalledFiles().size() >= 2);

        String shortId = mythicResults.get(0).getRecord().getBundleShortId();
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/" + shortId + "_Witch_pets.yml")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Skills/" + shortId + "_witchespets_skills.yml")));

        service.uninstallBundle(witches.getFileName().toString(), "MythicMobs");
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/" + shortId + "_Witch_pets.yml")));
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Skills/" + shortId + "_witchespets_skills.yml")));

        Path hydra = EXTERNAL_BUNDLES_DIRECTORY.resolve("Umbraeus - The Void Hydra.zip");
        assumeTrue(Files.exists(hydra), "Umbraeus bundle is not available.");
        copyToIncomingFolder(service, hydra);

        List<String> hydraPackages = service.listInstallablePackages(hydra.getFileName().toString());
        assertTrue(hydraPackages.contains("Blueprints"));
        assertTrue(hydraPackages.contains("MythicMobs"));

        List<BundleInstallResult> blueprintResults = service.installBundle(hydra.getFileName().toString(), "Blueprints");
        assertEquals(1, blueprintResults.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/Blueprints/PeachTree-Void_Hydra/void_hydra.bbmodel")));
        service.uninstallBundle(hydra.getFileName().toString(), "Blueprints");
        assertFalse(Files.exists(serverRoot.resolve("plugins/Blueprints/PeachTree-Void_Hydra/void_hydra.bbmodel")));
    }

    @Test
    void shouldIgnoreExternalNonZipFiles() throws Exception {
        assumeTrue(Files.isDirectory(EXTERNAL_BUNDLES_DIRECTORY), "External bundles directory is not available.");

        Path rarFile = EXTERNAL_BUNDLES_DIRECTORY.resolve("Config.rar");
        assumeTrue(Files.exists(rarFile), "External non-zip file is not available.");

        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        copyToIncomingFolder(service, rarFile);
        assertTrue(service.hasIgnoredIncomingFiles());
        assertTrue(service.listBundleStatusViews().stream().noneMatch(view -> view.getBundleName().equals(rarFile.getFileName().toString())));
    }

    private Path copyToIncomingFolder(BundleService service, Path archivePath) throws IOException {
        Path target = service.getIncomingBundleDirectory().toPath().resolve(archivePath.getFileName().toString());
        Files.copy(archivePath, target);
        return target;
    }

    private boolean isSupportedArchive(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".zip");
    }

    private String baseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }
}
