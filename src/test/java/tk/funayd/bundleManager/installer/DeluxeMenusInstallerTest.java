package tk.funayd.bundleManager.installer;

import org.junit.jupiter.api.Test;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.installer.deluxemenus.DeluxeMenusInstaller;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeluxeMenusInstallerTest {

    private final DeluxeMenusInstaller installer = new DeluxeMenusInstaller();

    @Test
    void shouldResolveFilesInsideGuiMenusAndSubdirectories() throws Exception {
        ResolvedBundleFile rootFile = installer.resolveFile("main.yml", "abc123").orElseThrow();
        ResolvedBundleFile nestedFile = installer.resolveFile("gui_menus/admin/submenu.yml", "abc123").orElseThrow();

        assertEquals("plugins/DeluxeMenus/gui_menus/main.yml", rootFile.getTargetRelativePath());
        assertEquals("plugins/DeluxeMenus/gui_menus/admin/submenu.yml", nestedFile.getTargetRelativePath());
    }

    @Test
    void shouldOnlyRenameMenuFileWhenConflictIsExplicitlyResolved() throws Exception {
        ResolvedBundleFile original = installer.resolveFile("gui_menus/admin/submenu.yml", "abc123").orElseThrow();
        ResolvedBundleFile renamed = installer.resolveRenameOnConflict(original, "abc123").orElseThrow();

        assertEquals("plugins/DeluxeMenus/gui_menus/admin/abc123_submenu.yml", renamed.getTargetRelativePath());
    }

    @Test
    void shouldRegisterMenuWithRelativeTargetFile() throws Exception {
        List<BundleRecord.ConfigMutation> mutations = installer.buildConfigMutations(List.of(
                new ResolvedBundleFile("gui_menus/admin/submenu.yml", "gui_menus/admin/submenu.yml",
                        "plugins/DeluxeMenus/gui_menus/admin/abc_submenu.yml")
        ), "abc");

        assertEquals(1, mutations.size());
        BundleRecord.ConfigMutation mutation = mutations.get(0);
        assertEquals("gui_menus.submenu", mutation.getTargetPath());
        assertEquals("admin/abc_submenu.yml", mutation.getValue());
    }
}
