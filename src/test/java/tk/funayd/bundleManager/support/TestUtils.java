package tk.funayd.bundleManager.support;

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
        Path dataFolder = serverRoot.resolve("plugins/BundleManager");
        Files.createDirectories(dataFolder);

        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BundleManagerTest"));
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
