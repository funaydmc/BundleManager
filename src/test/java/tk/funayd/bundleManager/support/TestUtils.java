package tk.funayd.bundleManager.support;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestUtils {

    private TestUtils() {
    }

    public static JavaPlugin mockPlugin(Path serverRoot) throws IOException {
        return mockPlugin(serverRoot,
                "Blueprints",
                "DeluxeMenus",
                "ItemsAdder",
                "MCPets",
                "MMOItems",
                "ModelEngine",
                "MythicLib",
                "MythicMobs",
                "Nexo",
                "Oraxen"
        );
    }

    public static JavaPlugin mockPlugin(Path serverRoot, String... installedPluginNames) throws IOException {
        Path dataFolder = serverRoot.resolve("plugins/BundleManager");
        Files.createDirectories(dataFolder);

        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Server server = Mockito.mock(Server.class);
        PluginManager pluginManager = Mockito.mock(PluginManager.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BundleManagerTest"));
        Mockito.when(plugin.getServer()).thenReturn(server);
        Mockito.when(server.getPluginManager()).thenReturn(pluginManager);
        Plugin[] installedPlugins = new Plugin[installedPluginNames.length];
        for (int index = 0; index < installedPluginNames.length; index++) {
            installedPlugins[index] = mockPluginEntry(installedPluginNames[index]);
        }
        Mockito.when(pluginManager.getPlugins()).thenReturn(installedPlugins);
        return plugin;
    }

    private static Plugin mockPluginEntry(String pluginName) {
        Plugin plugin = Mockito.mock(Plugin.class);
        PluginDescriptionFile description = Mockito.mock(PluginDescriptionFile.class);
        Mockito.when(description.getName()).thenReturn(pluginName);
        Mockito.when(plugin.getDescription()).thenReturn(description);
        return plugin;
    }

    public static Path createZip(Path zipPath, Map<String, String> entries) throws IOException {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
                outputStream.putNextEntry(new ZipEntry(entry.getKey()));
                outputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }
        }
        return zipPath;
    }

    public static byte[] createZipBytes(Map<String, String> entries) throws IOException {
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
                zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zipOutputStream.closeEntry();
            }
        }
        return outputStream.toByteArray();
    }

    public static String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
