package tk.funayd.bundleManager.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class DirectoryBundleArchive implements BundleArchive {

    private final Path sourceRoot;

    DirectoryBundleArchive(Path sourceRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    }

    @Override
    public List<BundleArchiveEntry> entries() throws BundleException {
        try (var paths = Files.walk(sourceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> sourceRoot.relativize(path).toString().replace('\\', '/'))
                    .map(path -> new BundleArchiveEntry(path, false))
                    .toList();
        } catch (IOException ex) {
            throw new BundleException("Failed to read bundle directory " + sourceRoot.getFileName() + ".", ex);
        }
    }

    @Override
    public InputStream openInputStream(BundleArchiveEntry entry) throws IOException, BundleException {
        Path target = sourceRoot.resolve(entry.getName().replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(sourceRoot) || !Files.isRegularFile(target)) {
            throw new BundleException("Bundle entry not found during install: " + entry.getName());
        }
        return Files.newInputStream(target);
    }

    @Override
    public void close() {
        // Directory archive does not keep any open handle.
    }
}
