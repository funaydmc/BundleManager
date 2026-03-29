package tk.funayd.bundleManager.installer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceRewriteContextTest {

    @Test
    void shouldMapRelativePathFileNameAndBaseName() throws Exception {
        ReferenceRewriteContext context = new ReferenceRewriteContext(List.of(
                new ResolvedBundleFile("main.yml", "main.yml", "plugins/DeluxeMenus/gui_menus/abc_main.yml"),
                new ResolvedBundleFile("submenu.yml", "submenu.yml", "plugins/DeluxeMenus/gui_menus/abc_submenu.yml")
        ));

        assertEquals("plugins/DeluxeMenus/gui_menus/abc_main.yml", context.renameRelativePath("main.yml").orElseThrow());
        assertEquals("abc_submenu.yml", context.renameFileName("submenu.yml").orElseThrow());
        assertEquals("abc_submenu", context.renameBaseName("submenu").orElseThrow());
    }

    @Test
    void shouldDropAmbiguousBasenameMappings() throws Exception {
        ReferenceRewriteContext context = new ReferenceRewriteContext(List.of(
                new ResolvedBundleFile("a/main.yml", "a/main.yml", "plugins/DeluxeMenus/gui_menus/a/abc_main.yml"),
                new ResolvedBundleFile("b/main.yml", "b/main.yml", "plugins/DeluxeMenus/gui_menus/b/xyz_main.yml")
        ));

        assertTrue(context.renameFileName("main.yml").isEmpty());
        assertTrue(context.renameBaseName("main").isEmpty());
    }
}
