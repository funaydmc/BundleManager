package tk.funayd.bundleManager.installer;

import org.junit.jupiter.api.Test;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.blueprints.BlueprintsInstaller;
import tk.funayd.bundleManager.installer.itemsadder.ItemsAdderInstaller;
import tk.funayd.bundleManager.installer.mcpets.MCPetsInstaller;
import tk.funayd.bundleManager.installer.mmoitems.MMOItemsInstaller;
import tk.funayd.bundleManager.installer.modelengine.ModelEngineInstaller;
import tk.funayd.bundleManager.installer.nexo.NexoInstaller;
import tk.funayd.bundleManager.installer.mythiclib.MythicLibInstaller;
import tk.funayd.bundleManager.installer.mythicmobs.MythicMobsInstaller;
import tk.funayd.bundleManager.installer.oraxen.OraxenInstaller;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginInstallerPathTest {

    @Test
    void itemsAdderShouldKeepContentsFolderAndCreateFallbackMutation() throws Exception {
        ItemsAdderInstaller installer = new ItemsAdderInstaller();

        ResolvedBundleFile resolved = installer.resolveFile("contents/my_pack/configs/example.yml", "abc").orElseThrow();
        List<BundleRecord.ConfigMutation> mutations = installer.buildConfigMutations(List.of(resolved), "abc");

        assertEquals("plugins/ItemsAdder/contents/my_pack/configs/example.yml", resolved.getTargetRelativePath());
        assertEquals(1, mutations.size());
        assertEquals("contents-folders-priorities||resource-pack.zip.contents-folders-priority", mutations.get(0).getTargetPath());
        assertEquals("my_pack", mutations.get(0).getValue());
    }

    @Test
    void mythicMobsShouldKeepIdsButHandleRealFolderLayouts() throws Exception {
        MythicMobsInstaller installer = new MythicMobsInstaller();

        ResolvedBundleFile mob = installer.resolveFile("Mobs/Zombie.yml", "abc").orElseThrow();
        ResolvedBundleFile skill = installer.resolveFile("Skills/fire.yml", "abc").orElseThrow();
        ResolvedBundleFile packMob = installer.resolveFile("Packs/MyPack/Mobs/Zombie.yml", "abc").orElseThrow();
        ResolvedBundleFile asset = installer.resolveFile("assets/textures/icon.png", "abc").orElseThrow();
        ResolvedBundleFile fontImages = installer.resolveFile("font-images.yml", "abc").orElseThrow();

        assertEquals("plugins/MythicMobs/Mobs/Zombie.yml", mob.getTargetRelativePath());
        assertEquals("plugins/MythicMobs/Skills/fire.yml", skill.getTargetRelativePath());
        assertEquals("plugins/MythicMobs/Packs/MyPack/Mobs/Zombie.yml", packMob.getTargetRelativePath());
        assertEquals("plugins/MythicMobs/assets/textures/icon.png", asset.getTargetRelativePath());
        assertEquals("plugins/MythicMobs/font-images.yml", fontImages.getTargetRelativePath());
        assertTrue(installer.resolveFile("Random/Folder/test.yml", "abc").isEmpty());
        assertTrue(installer.resolveFile("config.yml", "abc").isEmpty());
    }

    @Test
    void modularInstallersShouldKeepOriginalPathUntilConflictIsApproved() throws Exception {
        MMOItemsInstaller mmoItemsInstaller = new MMOItemsInstaller();
        MythicLibInstaller mythicLibInstaller = new MythicLibInstaller();
        MCPetsInstaller mcPetsInstaller = new MCPetsInstaller();
        ModelEngineInstaller modelEngineInstaller = new ModelEngineInstaller();
        BlueprintsInstaller blueprintsInstaller = new BlueprintsInstaller();

        assertEquals(
                "plugins/MMOItems/item/sword.yml",
                mmoItemsInstaller.resolveFile("item/sword.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/MythicLib/skill/pack.yml",
                mythicLibInstaller.resolveFile("skill/pack.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/MCPets/Pets/MyPack/pet.yml",
                mcPetsInstaller.resolveFile("Pets/MyPack/pet.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/MCPets/Pets/MyPack/abc_pet.yml",
                mcPetsInstaller.resolveRenameOnConflict(
                        mcPetsInstaller.resolveFile("Pets/MyPack/pet.yml", "abc").orElseThrow(),
                        "abc"
                ).orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/ModelEngine/blueprints/my_model.bbmodel",
                modelEngineInstaller.resolveFile("blueprints/my_model.bbmodel", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/Blueprints/MyPack/model.bbmodel",
                blueprintsInstaller.resolveFile("MyPack/model.bbmodel", "abc").orElseThrow().getTargetRelativePath()
        );
        assertTrue(modelEngineInstaller.resolveFile("config.yml", "abc").isEmpty());
        assertTrue(mcPetsInstaller.resolveFile("README.txt", "abc").isEmpty());
    }

    @Test
    void oraxenInstallerShouldOnlyAcceptContentDirectories() throws Exception {
        OraxenInstaller installer = new OraxenInstaller();

        assertEquals(
                "plugins/Oraxen/items/weapons/swords.yml",
                installer.resolveFile("items/weapons/swords.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/Oraxen/pack/textures/items/icon.png",
                installer.resolveFile("pack/textures/items/icon.png", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/Oraxen/glyphs/chat.yml",
                installer.resolveFile("glyphs/chat.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertTrue(installer.resolveFile("settings.yml", "abc").isEmpty());
        assertTrue(installer.resolveFile("mechanics/furniture.yml", "abc").isEmpty());
    }

    @Test
    void nexoInstallerShouldOnlyAcceptDocumentedContentDirectories() throws Exception {
        NexoInstaller installer = new NexoInstaller();

        assertEquals(
                "plugins/Nexo/items/weapons/swords.yml",
                installer.resolveFile("items/weapons/swords.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/Nexo/dialogs/vendor.yml",
                installer.resolveFile("dialogs/vendor.yml", "abc").orElseThrow().getTargetRelativePath()
        );
        assertEquals(
                "plugins/Nexo/pack/external_packs/MyPack/assets/example.json",
                installer.resolveFile("pack/external_packs/MyPack/assets/example.json", "abc").orElseThrow().getTargetRelativePath()
        );
        assertTrue(installer.resolveFile("config.yml", "abc").isEmpty());
        assertTrue(installer.resolveFile("mechanics/furniture.yml", "abc").isEmpty());
    }
}
