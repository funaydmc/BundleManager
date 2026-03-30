package tk.funayd.bundleManager.bundle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tk.funayd.bundleManager.support.TestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInstallOnlyRequestedPackageAndRewriteInternalReferences() throws Exception {
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

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-1.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "DeluxeMenus/main.yml", """
                        menu_title: "Main"
                        open_commands:
                          - "[openguimenu] submenu"
                        items: {}
                        """,
                "DeluxeMenus/submenu.yml", """
                        menu_title: "Sub"
                        items: {}
                        """,
                "ItemsAdder/contents/my_pack/configs/example.yml", "enabled: true\n"
        ));

        var results = service.installBundle("bundle-1", "DeluxeMenus");

        assertEquals(1, results.size());
        BundleRecord record = results.get(0).getRecord();
        assertEquals("DeluxeMenus", record.getPackageKey());

        String shortId = record.getBundleShortId();
        Path mainMenu = serverRoot.resolve("plugins/DeluxeMenus/gui_menus/main.yml");
        Path submenu = serverRoot.resolve("plugins/DeluxeMenus/gui_menus/submenu.yml");
        Path itemsAdderFile = serverRoot.resolve("plugins/ItemsAdder/contents/my_pack/configs/example.yml");

        assertTrue(Files.exists(mainMenu));
        assertTrue(Files.exists(submenu));
        assertFalse(Files.exists(itemsAdderFile));
        assertTrue(TestUtils.readString(mainMenu).contains("[openguimenu] submenu"));

        YamlConfiguration deluxeYaml = YamlConfiguration.loadConfiguration(deluxeConfig.toFile());
        assertEquals("main.yml", deluxeYaml.getString("gui_menus.main.file"));
        assertEquals(1, service.listInstalledBundles().size());
        assertEquals(List.of("DeluxeMenus"), service.listInstalledBundles().get(0).getPackageKeys());

        service.uninstallBundle("bundle-1", "DeluxeMenus");

        assertFalse(Files.exists(mainMenu));
        assertFalse(Files.exists(submenu));
        assertFalse(deluxeYamlAfter(deluxeConfig).contains("gui_menus.main"));
        assertTrue(service.listInstalledBundles().isEmpty());
    }

    @Test
    void shouldInstallAllPackagesAndAllowUninstallingOnePackageOnly() throws Exception {
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

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-2.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "DeluxeMenus/main.yml", "menu_title: \"Main\"\nitems: {}\n",
                "ItemsAdder/contents/my_pack/configs/example.yml", "enabled: true\n"
        ));

        var results = service.installBundle("bundle-2", null);

        assertEquals(2, results.size());
        assertEquals(1, service.listInstalledBundles().size());
        assertEquals(List.of("DeluxeMenus", "ItemsAdder"), service.listInstalledBundles().get(0).getPackageKeys());
        assertTrue(service.listInstalledPackageKeys("bundle-2").contains("ItemsAdder"));
        assertTrue(service.listInstalledPackageKeys("bundle-2").contains("DeluxeMenus"));

        service.uninstallBundle("bundle-2", "ItemsAdder");

        assertEquals(1, service.listInstalledBundles().size());
        assertEquals(List.of("DeluxeMenus"), service.listInstalledBundles().get(0).getPackageKeys());
        assertFalse(Files.exists(serverRoot.resolve("plugins/ItemsAdder/contents/my_pack/configs/example.yml")));

        YamlConfiguration itemsAdderYaml = YamlConfiguration.loadConfiguration(itemsAdderConfig.toFile());
        assertFalse(itemsAdderYaml.getStringList("contents-folders-priorities").contains("my_pack"));
    }

    @Test
    void shouldInstallImplicitItemsAdderContentFoldersUnderContents() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path itemsAdderConfig = serverRoot.resolve("plugins/ItemsAdder/config.yml");
        Files.createDirectories(itemsAdderConfig.getParent());
        Files.writeString(itemsAdderConfig, "contents-folders-priorities: []\n");

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/itemsadder-implicit.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "ItemsAdder/mystery_pack/configs/example.yml", "enabled: true\n"
        ));

        var results = service.installBundle("itemsadder-implicit", "ItemsAdder");

        assertEquals(1, results.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/ItemsAdder/contents/mystery_pack/configs/example.yml")));
        YamlConfiguration itemsAdderYaml = YamlConfiguration.loadConfiguration(itemsAdderConfig.toFile());
        assertTrue(itemsAdderYaml.getStringList("contents-folders-priorities").contains("mystery_pack"));
    }

    @Test
    void shouldRefuseToOverwriteExistingTargetFileDuringInstall() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-3.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "Type: ZOMBIE\nHealth: 20\n"
        ));

        Path targetFile = serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "Type: ZOMBIE\nHealth: 10\n");

        BundleException exception = assertThrows(BundleException.class, () -> service.installBundle("bundle-3", "MythicMobs"));
        assertTrue(exception.getMessage().contains("Refusing to overwrite existing file"));
        assertEquals("Type: ZOMBIE\nHealth: 10\n", TestUtils.readString(targetFile));
        assertTrue(service.listInstalledBundles().isEmpty());
    }

    @Test
    void shouldQueueOverwriteConflictAndRestoreOriginalFileAfterApprovedInstallIsUninstalled() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path targetFile = serverRoot.resolve("plugins/ModelEngine/blueprints/model.yml");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "model: old\n");

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/modelengine-conflict.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "ModelEngine/blueprints/model.yml", "model: new\n"
        ));

        BundleException exception = assertThrows(BundleException.class, () -> service.installBundle("modelengine-conflict", "ModelEngine"));
        assertTrue(exception.getMessage().contains("Overwrite conflict queued as #1"));
        assertEquals(1, service.listOverwriteConflicts().size());
        assertEquals(List.of("plugins/ModelEngine/blueprints/model.yml"), service.listOverwriteConflicts().get(0).getTargetPaths());
        assertEquals("model: old\n", TestUtils.readString(targetFile));

        BundleActionReport report = service.resolveOverwriteConflict("1", true);
        assertTrue(report.getSucceededPackages().contains("ModelEngine"));
        assertEquals("model: new\n", TestUtils.readString(targetFile));
        assertTrue(service.listOverwriteConflicts().isEmpty());

        service.uninstallBundle("1", "ModelEngine");
        assertEquals("model: old\n", TestUtils.readString(targetFile));
    }

    @Test
    void shouldRenameDeluxeMenusFileOnlyWhenNaturalTargetAlreadyExists() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path deluxeConfig = serverRoot.resolve("plugins/DeluxeMenus/config.yml");
        Path existingMenu = serverRoot.resolve("plugins/DeluxeMenus/gui_menus/main.yml");
        Files.createDirectories(existingMenu.getParent());
        Files.writeString(deluxeConfig, "gui_menus: {}\n");
        Files.writeString(existingMenu, "menu_title: \"Existing\"\nitems: {}\n");

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/deluxe-conflict.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "DeluxeMenus/main.yml", "menu_title: \"Bundle\"\nitems: {}\n"
        ));

        List<BundleInstallResult> results = service.installBundle("deluxe-conflict", "DeluxeMenus");

        assertEquals(1, results.size());
        assertEquals("menu_title: \"Existing\"\nitems: {}\n", TestUtils.readString(existingMenu));
        assertTrue(Files.exists(serverRoot.resolve("plugins/DeluxeMenus/gui_menus/1_main.yml")));
        YamlConfiguration deluxeYaml = YamlConfiguration.loadConfiguration(deluxeConfig.toFile());
        assertEquals("1_main.yml", deluxeYaml.getString("gui_menus.main.file"));
    }

    @Test
    void shouldResolveServerRootFromRelativePluginDataFolder() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);

        java.lang.reflect.Method method = BundleService.class.getDeclaredMethod("deriveServerRoot", java.io.File.class);
        method.setAccessible(true);

        Path resolved = (Path) method.invoke(service, new java.io.File("plugins/BundleManager"));
        Path expected = new java.io.File("plugins/BundleManager").getAbsoluteFile()
                .toPath()
                .getParent()
                .getParent()
                .normalize();

        assertEquals(expected, resolved);
    }

    @Test
    void shouldWarnWhenDeluxeMenusMenuIdAlreadyExists() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path deluxeConfig = serverRoot.resolve("plugins/DeluxeMenus/config.yml");
        Files.createDirectories(deluxeConfig.getParent());
        Files.writeString(deluxeConfig, """
                gui_menus:
                  main:
                    file: existing_main.yml
                """);

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-conflict.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "DeluxeMenus/main.yml", "menu_title: \"Main\"\nitems: {}\n"
        ));

        List<BundleInstallResult> results = service.installBundle("bundle-conflict", "DeluxeMenus");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getWarnings().stream().anyMatch(message -> message.contains("menu id 'main'")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/DeluxeMenus/gui_menus/main.yml")));
        assertEquals(1, service.listInstalledBundles().size());
    }

    @Test
    void shouldWarnWhenMythicMobIdAlreadyExists() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path existingMobFile = serverRoot.resolve("plugins/MythicMobs/Mobs/existing.yml");
        Files.createDirectories(existingMobFile.getParent());
        Files.writeString(existingMobFile, """
                knight:
                  Type: ZOMBIE
                """);

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-mythic-conflict.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/KnightBundle.yml", """
                        knight:
                          Type: SKELETON
                        """
        ));

        List<BundleInstallResult> results = service.installBundle("bundle-mythic-conflict", "MythicMobs");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getWarnings().stream().anyMatch(message -> message.contains("mob id 'knight'")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/KnightBundle.yml")));
        assertEquals(1, service.listInstalledBundles().size());
    }

    @Test
    void shouldAllowSameMythicKeyAcrossMobAndSkillNamespacesInsidePack() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-pack-namespaces.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Packs/LostAssets/Mobs/LostAssets.yml", """
                        lostassets_revenant_lc_2:
                          Type: ZOMBIE
                        """,
                "MythicMobs/Packs/LostAssets/Skills/LostAssets.yml", """
                        lostassets_revenant_lc_2:
                          Cooldown: 10
                        """
        ));

        assertDoesNotThrow(() -> service.installBundle("bundle-pack-namespaces", "MythicMobs"));
        assertEquals(List.of("MythicMobs"), service.listInstalledBundles().get(0).getPackageKeys());
    }

    @Test
    void shouldTreatSingleDefinitionMythicSkillFilesAsFilenameIds() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-single-skill-files.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/skills/piglin_skills/mini_piglin_skills.yml", """
                        Cooldown: 5
                        Skills:
                          - message{m=mini}
                          - CancelEvent
                        """,
                "MythicMobs/skills/piglin_skills/piglin_boss_skills.yml", """
                        Cooldown: 10
                        Skills:
                          - message{m=boss}
                          - CancelEvent
                        """
        ));

        assertDoesNotThrow(() -> service.installBundle("bundle-single-skill-files", "MythicMobs"));
        assertEquals(List.of("MythicMobs"), service.listInstalledBundles().get(0).getPackageKeys());
    }

    @Test
    void shouldIgnoreNonZipFilesInIncomingFolder() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Files.writeString(serverRoot.resolve("plugins/BundleManager/bundles/notes.txt"), "skip me");

        assertTrue(service.hasIgnoredIncomingFiles());
        assertTrue(service.listBundleStatusViews().isEmpty());
        assertEquals(List.of(), service.listKnownBundleIds());
    }

    @Test
    void shouldHideUnsupportedPackagesInBundleStatus() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-unsupported.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n",
                "UnknownPlugin/config.yml", "enabled: true\n"
        ));

        List<BundleStatusView> views = service.listBundleStatusViews();

        assertEquals(1, views.size());
        assertEquals(BundleOverallState.FAILED, views.get(0).getOverallState());
        assertTrue(views.get(0).getPackageViews().stream().anyMatch(view ->
                view.getPackageKey().equals("MythicMobs") && view.getState() == BundlePackageState.FAILED));
        assertTrue(views.get(0).getPackageViews().stream().noneMatch(view ->
                view.getPackageKey().equals("UnknownPlugin")));
    }

    @Test
    void shouldDisplayBasePluginNameWhenInternalPackageKeyHasSingleVariantSuffix() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/SERVER_MENU_BUNDLE");
        Files.createDirectories(bundleRoot.resolve("DeluxeMenus (server_menu)"));
        Files.writeString(bundleRoot.resolve("DeluxeMenus (server_menu)/main.yml"), "menu_title: \"Main\"\nitems: {}\n");

        List<BundleStatusView> views = service.listBundleStatusViews();

        assertEquals(1, views.size());
        assertEquals(1, views.get(0).getPackageViews().size());
        assertEquals("DeluxeMenus", views.get(0).getPackageViews().get(0).getDisplayName());
    }

    @Test
    void shouldIgnoreRootFilesWhenDetectingPackages() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-root-files.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "readme.txt", "bundle notes",
                "license.yml", "name: test\n",
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n"
        ));

        List<BundleStatusView> views = service.listBundleStatusViews();

        assertEquals(1, views.size());
        assertEquals(List.of("MythicMobs"), service.listInstallablePackages("bundle-root-files"));
        assertTrue(views.get(0).getPackageViews().stream().noneMatch(view -> view.getPackageKey().equals("readme.txt")));
        assertTrue(views.get(0).getPackageViews().stream().noneMatch(view -> view.getPackageKey().equals("license.yml")));
        assertDoesNotThrow(() -> service.installBundle("bundle-root-files", "MythicMobs"));
    }

    @Test
    void shouldDetectOraxenAndNexoPackages() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/bundle-content-platforms.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "Oraxen/items/weapons.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n",
                "Nexo/items/weapons.yml", "onyx_sword:\n  material: DIAMOND_SWORD\n",
                "Nexo/pack/external_packs/MyPack/assets/nexo/item.json", "{}\n"
        ));

        assertEquals(List.of("Nexo", "Oraxen"), service.listInstallablePackages("bundle-content-platforms"));
    }

    @Test
    void shouldInstallImplicitNexoContentFolders() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/nexo-implicit.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "Nexo/mystery_pack/items/weapons.yml", "onyx_sword:\n  material: DIAMOND_SWORD\n"
        ));

        var results = service.installBundle("nexo-implicit", "Nexo");

        assertEquals(1, results.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/Nexo/mystery_pack/items/weapons.yml")));
    }

    @Test
    void shouldInstallPackagesFromNestedZipInsideBundleZip() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/outer-bundle.zip");
        Files.createDirectories(bundleZip.getParent());
        byte[] nestedZipBytes = TestUtils.createZipBytes(Map.of(
                "MythicMobs/Mobs/Zombie.yml", """
                        zombie:
                          Type: ZOMBIE
                        """
        ));
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(bundleZip))) {
            outputStream.putNextEntry(new ZipEntry("content.zip"));
            outputStream.write(nestedZipBytes);
            outputStream.closeEntry();
        }

        assertEquals(List.of("MythicMobs"), service.listInstallablePackages("outer-bundle"));

        List<BundleInstallResult> results = service.installBundle("outer-bundle", "MythicMobs");
        assertEquals(1, results.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml")));
    }

    @Test
    void shouldUninstallBundleWhenSourceIsRemovedOnReload() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/removed-bundle.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n"
        ));

        BundleLoadReport firstLoad = service.autoLoadBundles();
        assertEquals(1, firstLoad.getInstalledPackageCount());
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml")));

        Files.delete(bundleZip);

        BundleLoadReport reload = service.autoLoadBundles();
        assertEquals(0, reload.getInstalledPackageCount());
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml")));
        assertTrue(service.listInstalledBundles().isEmpty());
        assertTrue(reload.getWarnings().stream().anyMatch(message -> message.contains("no longer exists")));
    }

    @Test
    void shouldReinstallBundleWhenSourceSha1ChangesOnReload() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/changed-bundle.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n  Health: 20\n"
        ));

        BundleLoadReport firstLoad = service.autoLoadBundles();
        assertEquals(1, firstLoad.getInstalledPackageCount());
        assertTrue(TestUtils.readString(serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml")).contains("Health: 20"));

        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n  Health: 40\n"
        ));

        BundleLoadReport reload = service.autoLoadBundles();
        assertEquals(1, reload.getInstalledPackageCount());
        assertEquals(1, reload.getInstalledBundleCount());
        assertTrue(TestUtils.readString(serverRoot.resolve("plugins/MythicMobs/Mobs/Zombie.yml")).contains("Health: 40"));
        assertTrue(reload.getWarnings().stream().anyMatch(message -> message.contains("Reinstalled from updated source")));
        YamlConfiguration preference = YamlConfiguration.loadConfiguration(serverRoot.resolve("plugins/BundleManager/data/preferences/1.yml").toFile());
        assertFalse(preference.getString("sourceSha1", "").isBlank());
    }

    @Test
    void shouldReportAlreadyActivePackagesOnUnchangedReload() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/stable-bundle.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "MythicMobs/Mobs/Zombie.yml", "zombie:\n  Type: ZOMBIE\n"
        ));

        BundleLoadReport firstLoad = service.autoLoadBundles();
        assertEquals(1, firstLoad.getInstalledPackageCount());
        assertEquals(1, firstLoad.getInstalledBundleCount());

        BundleLoadReport secondLoad = service.autoLoadBundles();
        assertEquals(1, secondLoad.getInstalledPackageCount());
        assertEquals(1, secondLoad.getInstalledBundleCount());
        assertTrue(secondLoad.getWarnings().isEmpty());
        assertTrue(secondLoad.getErrors().isEmpty());
    }

    @Test
    void shouldIncludeBundleAndPackageContextInAutoLoadWarningsForMultiPackageBundles() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path deluxeConfig = serverRoot.resolve("plugins/DeluxeMenus/config.yml");
        Path itemsAdderConfig = serverRoot.resolve("plugins/ItemsAdder/config.yml");
        Files.createDirectories(deluxeConfig.getParent());
        Files.createDirectories(itemsAdderConfig.getParent());
        Files.writeString(deluxeConfig, """
                gui_menus:
                  main:
                    file: existing_main.yml
                """);
        Files.writeString(itemsAdderConfig, "contents-folders-priorities: []\n");

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/contextual-warning.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "DeluxeMenus/main.yml", "menu_title: \"Main\"\nitems: {}\n",
                "ItemsAdder/contents/my_pack/configs/example.yml", "enabled: true\n"
        ));

        BundleLoadReport report = service.autoLoadBundles();

        assertEquals(2, report.getInstalledPackageCount());
        assertTrue(report.getWarnings().stream().anyMatch(message ->
                message.contains("[contextual-warning.zip | DeluxeMenus]")
                        && message.contains("menu id 'main'")
        ));
    }

    @Test
    void shouldWarnWhenSupportedPluginPackageExistsButPluginIsNotInstalled() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot, "MythicMobs");
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/itemsadder-missing.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "ItemsAdder/contents/my_pack/configs/example.yml", "enabled: true\n"
        ));

        BundleLoadReport report = service.autoLoadBundles();

        assertEquals(0, report.getInstalledPackageCount());
        assertTrue(report.getWarnings().isEmpty());
        assertTrue(report.hasMissingPlugins());
        assertEquals("ItemsAdder", report.getMissingPlugins().get(0).pluginName());
        assertEquals(List.of("1"), report.getMissingPlugins().get(0).bundleIds());
    }

    @Test
    void shouldWarnWhenInstalledPluginAppearsInBundleButInstallerIsMissing() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot, "AdvancedEnchantments");
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/unsupported-installed-plugin.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "AdvancedEnchantments/books/example.yml", "test: true\n"
        ));

        BundleLoadReport report = service.autoLoadBundles();

        assertEquals(0, report.getInstalledPackageCount());
        assertTrue(report.getWarnings().stream().anyMatch(message ->
                message.contains("[unsupported-installed-plugin.zip]")
                        && message.contains("Plugin 'AdvancedEnchantments' is installed on the server")
                        && message.contains("https://github.com/funaydmc/BundleManager/issues")
        ));
    }

    @Test
    void shouldInstallRootResourcePackToBundleManagerPackDirectory() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleZip = serverRoot.resolve("plugins/BundleManager/bundles/resource-pack.zip");
        TestUtils.createZip(bundleZip, Map.of(
                "pack.mcmeta", """
                        {
                          "pack": {
                            "pack_format": 34,
                            "description": "Test Pack"
                          }
                        }
                        """,
                "assets/minecraft/models/item/stick.json", "{\"parent\":\"item/generated\"}\n"
        ));

        assertEquals(List.of("ResourcePack"), service.listInstallablePackages("resource-pack"));

        List<BundleInstallResult> results = service.installBundle("resource-pack", "ResourcePack");
        assertEquals(1, results.size());
        assertTrue(Files.exists(serverRoot.resolve("plugins/BundleManager/pack/1/pack.mcmeta")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/BundleManager/pack/1/assets/minecraft/models/item/stick.json")));
    }

    @Test
    void shouldDiscoverResourcePackVariantsInsideDirectoryBundle() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/PACK_BUNDLE");
        Files.createDirectories(bundleRoot.resolve("vanilla/Pack"));
        Files.createDirectories(bundleRoot.resolve("model/Pack"));
        Files.createDirectories(bundleRoot.resolve("vanilla/Pack/assets"));
        Files.createDirectories(bundleRoot.resolve("model/Pack/assets"));
        Files.writeString(bundleRoot.resolve("vanilla/Pack/pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        Files.writeString(bundleRoot.resolve("model/Pack/pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        Files.writeString(bundleRoot.resolve("vanilla/Pack/assets/example.txt"), "vanilla");
        Files.writeString(bundleRoot.resolve("model/Pack/assets/example.txt"), "model");

        assertEquals(List.of("ResourcePack@model", "ResourcePack@vanilla"), service.listInstallablePackages("PACK_BUNDLE"));

        service.autoLoadBundles();

        assertEquals(1, service.listInstalledBundles().size());
        String bundleId = service.listInstalledBundles().get(0).getBundleShortId();
        List<String> promptMessages = service.openVariantPrompt(bundleId);
        List<BundleStatusView> views = service.listBundleStatusViews();
        assertEquals(1, views.size());
        assertTrue(views.get(0).getPackageViews().stream().anyMatch(view -> view.getDisplayName().equals("ResourcePack [2]")));
        assertTrue(promptMessages.stream().anyMatch(message -> message.contains("--- ResourcePack ---")));
        assertEquals(2, promptMessages.stream().filter(message -> message.startsWith("    ")).count());
        assertTrue(Files.exists(serverRoot.resolve("plugins/BundleManager/pack/1_model/pack.mcmeta"))
                || Files.exists(serverRoot.resolve("plugins/BundleManager/pack/1_vanilla/pack.mcmeta")));
    }

    @Test
    void shouldDiscoverVariantPackagesInsideDirectoryBundle() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/MEGA_BUNDLE");
        Files.createDirectories(bundleRoot.resolve("vanilla/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("MythicMobs (model)/Mobs"));
        Files.writeString(bundleRoot.resolve("vanilla/MythicMobs/Mobs/VanillaMob.yml"), """
                vanilla_knight:
                  Type: ZOMBIE
                """);
        Files.writeString(bundleRoot.resolve("MythicMobs (model)/Mobs/ModelMob.yml"), """
                model_knight:
                  Type: SKELETON
                """);

        assertEquals(List.of("MythicMobs@model", "MythicMobs@vanilla"), service.listInstallablePackages("MEGA_BUNDLE"));
        service.autoLoadBundles();

        assertEquals(1, service.listInstalledBundles().size());
        List<String> installedPackages = service.listInstalledBundles().get(0).getPackageKeys();
        assertEquals(1, installedPackages.size());
        assertTrue(installedPackages.get(0).startsWith("MythicMobs@"));
        String bundleId = service.listInstalledBundles().get(0).getBundleShortId();
        List<String> promptMessages = service.openVariantPrompt(bundleId);
        assertEquals(List.of("1", "2"), service.listPendingVariantIndexes());
        assertTrue(promptMessages.stream().anyMatch(message -> message.contains("--- MythicMobs ---")));
        assertTrue(promptMessages.stream().noneMatch(message -> message.contains("--- Bundle ---")));
        assertEquals(2, promptMessages.stream().filter(message -> message.startsWith("    ")).count());

        List<BundleStatusView> views = service.listBundleStatusViews();
        assertEquals(1, views.size());
        assertTrue(views.get(0).getPackageViews().stream().anyMatch(view -> view.getDisplayName().equals("MythicMobs [2]")));

        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/ModelMob.yml")));
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/VanillaMob.yml")));

        BundleActionReport switchReport = service.switchVariant(2);
        assertTrue(switchReport.getSucceededPackages().contains("MythicMobs@vanilla"));
        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/VanillaMob.yml")));
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/ModelMob.yml")));
    }

    @Test
    void shouldDisableVariantPackageByBasePluginName() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/ORAXEN_VARIANTS");
        Files.createDirectories(bundleRoot.resolve("vanilla/Oraxen/items"));
        Files.createDirectories(bundleRoot.resolve("modded/Oraxen/items"));
        Files.writeString(bundleRoot.resolve("vanilla/Oraxen/items/vanilla.yml"), "vanilla_sword:\n  material: DIAMOND_SWORD\n");
        Files.writeString(bundleRoot.resolve("modded/Oraxen/items/modded.yml"), "modded_sword:\n  material: NETHERITE_SWORD\n");

        service.autoLoadBundles();

        String bundleId = service.listInstalledBundles().get(0).getBundleShortId();
        assertEquals(List.of("Oraxen"), service.listKnownPackageKeys(bundleId));

        BundleActionReport disableReport = service.disableBundleById(bundleId, "Oraxen");
        assertEquals(List.of("Oraxen"), disableReport.getDisabledPackages());
        assertTrue(service.listInstalledPackageKeys(bundleId).isEmpty());

        service.autoLoadBundles();

        assertTrue(service.listInstalledPackageKeys(bundleId).isEmpty());
    }

    @Test
    void shouldBuildBundleVariantPromptWhenMultiplePackagesShareVariantNames() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/SHARED_VARIANTS");
        Files.createDirectories(bundleRoot.resolve("vanilla/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("modded/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("vanilla/ModelEngine/blueprints"));
        Files.createDirectories(bundleRoot.resolve("modded/ModelEngine/blueprints"));
        Files.writeString(bundleRoot.resolve("vanilla/MythicMobs/Mobs/VanillaMob.yml"), "vanilla_knight:\n  Type: ZOMBIE\n");
        Files.writeString(bundleRoot.resolve("modded/MythicMobs/Mobs/ModdedMob.yml"), "modded_knight:\n  Type: SKELETON\n");
        Files.writeString(bundleRoot.resolve("vanilla/ModelEngine/blueprints/vanilla_model.yml"), "model: vanilla\n");
        Files.writeString(bundleRoot.resolve("modded/ModelEngine/blueprints/modded_model.yml"), "model: modded\n");

        service.autoLoadBundles();

        List<String> messages = service.openVariantPrompt(service.listInstalledBundles().get(0).getBundleShortId());
        assertTrue(messages.stream().anyMatch(message -> message.contains("Multiple variant detected")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("--- Bundle ---")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("--- MythicMobs ---")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("--- ModelEngine ---")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("1. modded") || message.contains("1. vanilla")));
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), service.listPendingVariantIndexes());
    }

    @Test
    void packageVariantShouldOverrideBundleVariantSelection() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/OVERRIDE_VARIANTS");
        Files.createDirectories(bundleRoot.resolve("vanilla/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("modded/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("vanilla/ModelEngine/blueprints"));
        Files.createDirectories(bundleRoot.resolve("modded/ModelEngine/blueprints"));
        Files.writeString(bundleRoot.resolve("vanilla/MythicMobs/Mobs/VanillaMob.yml"), "vanilla_knight:\n  Type: ZOMBIE\n");
        Files.writeString(bundleRoot.resolve("modded/MythicMobs/Mobs/ModdedMob.yml"), "modded_knight:\n  Type: SKELETON\n");
        Files.writeString(bundleRoot.resolve("vanilla/ModelEngine/blueprints/vanilla_model.yml"), "model: vanilla\n");
        Files.writeString(bundleRoot.resolve("modded/ModelEngine/blueprints/modded_model.yml"), "model: modded\n");

        service.autoLoadBundles();
        service.openVariantPrompt(service.listInstalledBundles().get(0).getBundleShortId());
        service.switchVariant(2);
        service.openVariantPrompt(service.listInstalledBundles().get(0).getBundleShortId());
        service.switchVariant(5);
        service.autoLoadBundles();

        assertTrue(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/ModdedMob.yml")));
        assertFalse(Files.exists(serverRoot.resolve("plugins/MythicMobs/Mobs/VanillaMob.yml")));
        assertTrue(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/vanilla_model.yml")));
        assertFalse(Files.exists(serverRoot.resolve("plugins/ModelEngine/blueprints/modded_model.yml")));
    }

    @Test
    void shouldFindSupportedPluginFoldersRecursivelyAndReduceVariantFromFullPath() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        Path bundleRoot = serverRoot.resolve("plugins/BundleManager/bundles/NESTED_VARIANTS");
        Files.createDirectories(bundleRoot.resolve("shared/vanilla/content/MythicMobs/Mobs"));
        Files.createDirectories(bundleRoot.resolve("shared/modded/content/MythicMobs/Mobs"));
        Files.writeString(bundleRoot.resolve("shared/vanilla/content/MythicMobs/Mobs/VanillaMob.yml"), "vanilla:\n  Type: ZOMBIE\n");
        Files.writeString(bundleRoot.resolve("shared/modded/content/MythicMobs/Mobs/ModdedMob.yml"), "modded:\n  Type: SKELETON\n");

        assertEquals(List.of("MythicMobs@modded", "MythicMobs@vanilla"), service.listInstallablePackages("NESTED_VARIANTS"));

        service.autoLoadBundles();

        List<String> promptMessages = service.openVariantPrompt(service.listInstalledBundles().get(0).getBundleShortId());
        assertTrue(promptMessages.stream().anyMatch(message -> message.contains("--- MythicMobs ---")));
        assertTrue(promptMessages.stream().anyMatch(message -> message.contains("1. modded") || message.contains("1. vanilla")));
        assertTrue(service.listBundleStatusViews().get(0).getPackageViews().stream()
                .anyMatch(view -> view.getDisplayName().equals("MythicMobs [2]")));
    }

    @Test
    void shouldNotMatchOtherBundleIdsByPrefixWhenListingPackageState() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        TestUtils.createZip(serverRoot.resolve("plugins/BundleManager/bundles/Bundle One.zip"), Map.of(
                "AdvancedEnchantments/config.yml", "enabled: true\n"
        ));
        TestUtils.createZip(serverRoot.resolve("plugins/BundleManager/bundles/Bundle Ten.zip"), Map.of(
                "ItemsAdder/contents/my_pack/configs/example.yml", "enabled: true\n"
        ));
        Files.writeString(serverRoot.resolve("plugins/BundleManager/data/bundle-index.yml"), """
                entries:
                  bundle one_zip: '1'
                  bundle ten_zip: '10'
                nextId: 11
                """);
        Files.createDirectories(serverRoot.resolve("plugins/ItemsAdder"));
        Files.writeString(serverRoot.resolve("plugins/ItemsAdder/config.yml"), "contents-folders-priorities: []\n");

        service.installBundle("Bundle Ten", "ItemsAdder");

        List<BundleStatusView> views = service.listBundleStatusViews();
        BundleStatusView bundleOne = views.stream()
                .filter(view -> view.getBundleId().equals("1"))
                .findFirst()
                .orElseThrow();

        assertTrue(bundleOne.getPackageViews().isEmpty());
    }

    @Test
    void shouldSortBundleStatusViewsByNumericBundleId() throws Exception {
        Path serverRoot = tempDir.resolve("server");
        JavaPlugin plugin = TestUtils.mockPlugin(serverRoot);
        BundleService service = new BundleService(plugin);
        service.initialize();

        TestUtils.createZip(serverRoot.resolve("plugins/BundleManager/bundles/Bundle One.zip"), Map.of(
                "MythicMobs/Mobs/One.yml", "one:\n  Type: ZOMBIE\n"
        ));
        TestUtils.createZip(serverRoot.resolve("plugins/BundleManager/bundles/Bundle Two.zip"), Map.of(
                "MythicMobs/Mobs/Two.yml", "two:\n  Type: ZOMBIE\n"
        ));
        TestUtils.createZip(serverRoot.resolve("plugins/BundleManager/bundles/Bundle Ten.zip"), Map.of(
                "MythicMobs/Mobs/Ten.yml", "ten:\n  Type: ZOMBIE\n"
        ));
        Files.writeString(serverRoot.resolve("plugins/BundleManager/data/bundle-index.yml"), """
                entries:
                  bundle one_zip: '1'
                  bundle two_zip: '2'
                  bundle ten_zip: '10'
                nextId: 11
                """);

        List<BundleStatusView> views = service.listBundleStatusViews();

        assertEquals(List.of("1", "2", "10"), views.stream().map(BundleStatusView::getBundleId).toList());
    }

    private YamlConfiguration deluxeYamlAfter(Path configPath) {
        return YamlConfiguration.loadConfiguration(configPath.toFile());
    }
}
