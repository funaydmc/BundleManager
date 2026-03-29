package tk.funayd.bundleManager.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

interface BundleArchive extends AutoCloseable {

    List<BundleArchiveEntry> entries() throws BundleException;

    InputStream openInputStream(BundleArchiveEntry entry) throws IOException, BundleException;

    @Override
    void close() throws IOException;
}
