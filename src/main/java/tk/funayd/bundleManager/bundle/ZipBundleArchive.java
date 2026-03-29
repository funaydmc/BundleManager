package tk.funayd.bundleManager.bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class ZipBundleArchive implements BundleArchive {

    private final ZipFile zipFile;
    private final List<BundleArchiveEntry> bundleEntries;
    private final Map<String, byte[]> nestedEntryBytes;

    ZipBundleArchive(java.io.File sourceFile) throws IOException {
        this.zipFile = new ZipFile(sourceFile);
        this.nestedEntryBytes = new LinkedHashMap<>();
        this.bundleEntries = buildEntries();
    }

    @Override
    public List<BundleArchiveEntry> entries() {
        return bundleEntries;
    }

    @Override
    public InputStream openInputStream(BundleArchiveEntry entry) throws IOException, BundleException {
        byte[] nestedBytes = nestedEntryBytes.get(entry.getName());
        if (nestedBytes != null) {
            return new ByteArrayInputStream(nestedBytes);
        }

        ZipEntry zipEntry = zipFile.getEntry(entry.getName());
        if (zipEntry == null) {
            throw new BundleException("Bundle entry not found during install: " + entry.getName());
        }
        return zipFile.getInputStream(zipEntry);
    }

    @Override
    public void close() throws IOException {
        zipFile.close();
    }

    private List<BundleArchiveEntry> buildEntries() throws IOException {
        ArrayList<BundleArchiveEntry> entries = new ArrayList<>();
        for (ZipEntry zipEntry : zipFile.stream().toList()) {
            if (zipEntry.isDirectory()) {
                entries.add(new BundleArchiveEntry(zipEntry.getName(), true));
                continue;
            }

            if (zipEntry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                    expandNestedZip(zipEntry.getName(), inputStream.readAllBytes(), entries);
                }
                continue;
            }

            entries.add(new BundleArchiveEntry(zipEntry.getName(), false));
        }
        return List.copyOf(entries);
    }

    private void expandNestedZip(String zipEntryName, byte[] zipBytes, List<BundleArchiveEntry> entries) throws IOException {
        entries.add(new BundleArchiveEntry(zipEntryName, true));
        try (ZipInputStream nestedZip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry nestedEntry;
            while ((nestedEntry = nestedZip.getNextEntry()) != null) {
                String nestedName = zipEntryName + "/" + nestedEntry.getName();
                if (nestedEntry.isDirectory()) {
                    entries.add(new BundleArchiveEntry(nestedName, true));
                    continue;
                }

                byte[] entryBytes = readCurrentZipEntry(nestedZip);
                if (nestedEntry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    expandNestedZip(nestedName, entryBytes, entries);
                    continue;
                }

                entries.add(new BundleArchiveEntry(nestedName, false));
                nestedEntryBytes.put(nestedName, entryBytes);
            }
        }
    }

    private byte[] readCurrentZipEntry(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zipInputStream.read(buffer)) >= 0) {
            if (read > 0) {
                outputStream.write(buffer, 0, read);
            }
        }
        return outputStream.toByteArray();
    }
}
