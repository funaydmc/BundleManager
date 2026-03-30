package tk.funayd.bundleManager.bundle;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipException;

final class ZipBundleArchive implements BundleArchive {

    private final ZipFile zipFile;
    private final List<BundleArchiveEntry> bundleEntries;
    private final Map<String, byte[]> entryBytes;
    private final boolean streamBacked;

    ZipBundleArchive(java.io.File sourceFile, boolean preferStreaming) throws IOException {
        this.entryBytes = new LinkedHashMap<>();

        ZipFile openedZipFile = null;
        List<BundleArchiveEntry> entries;
        boolean openedFromStream = preferStreaming;
        if (preferStreaming) {
            entries = buildEntriesFromStream(sourceFile);
        } else {
            try {
                openedZipFile = new ZipFile(sourceFile);
                entries = buildEntries(openedZipFile);
            } catch (ZipException ex) {
                // Some bundles have malformed central-directory metadata that ZipFile rejects,
                // but their local file headers are still readable sequentially.
                entries = buildEntriesFromStream(sourceFile);
                openedFromStream = true;
            }
        }

        this.zipFile = openedZipFile;
        this.bundleEntries = entries;
        this.streamBacked = openedFromStream;
    }

    @Override
    public List<BundleArchiveEntry> entries() {
        return bundleEntries;
    }

    @Override
    public InputStream openInputStream(BundleArchiveEntry entry) throws IOException, BundleException {
        byte[] storedBytes = entryBytes.get(entry.getName());
        if (storedBytes != null) {
            return new ByteArrayInputStream(storedBytes);
        }

        if (zipFile == null) {
            throw new BundleException("Bundle entry not found during install: " + entry.getName());
        }
        ZipEntry zipEntry = zipFile.getEntry(entry.getName());
        if (zipEntry == null) {
            throw new BundleException("Bundle entry not found during install: " + entry.getName());
        }
        return zipFile.getInputStream(zipEntry);
    }

    @Override
    public void close() throws IOException {
        if (zipFile != null) {
            zipFile.close();
        }
    }

    boolean isStreamBacked() {
        return streamBacked;
    }

    private List<BundleArchiveEntry> buildEntries(ZipFile sourceZipFile) throws IOException {
        ArrayList<BundleArchiveEntry> entries = new ArrayList<>();
        for (ZipEntry zipEntry : sourceZipFile.stream().toList()) {
            if (zipEntry.isDirectory()) {
                entries.add(new BundleArchiveEntry(zipEntry.getName(), true));
                continue;
            }

            if (zipEntry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                try (InputStream inputStream = sourceZipFile.getInputStream(zipEntry)) {
                    expandNestedZip(zipEntry.getName(), inputStream.readAllBytes(), entries);
                }
                continue;
            }

            entries.add(new BundleArchiveEntry(zipEntry.getName(), false));
        }
        return List.copyOf(entries);
    }

    private List<BundleArchiveEntry> buildEntriesFromStream(java.io.File sourceFile) throws IOException {
        ArrayList<BundleArchiveEntry> entries = new ArrayList<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(sourceFile)))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    entries.add(new BundleArchiveEntry(zipEntry.getName(), true));
                    continue;
                }

                byte[] currentEntryBytes = readCurrentZipEntry(zipInputStream);
                if (zipEntry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    expandNestedZip(zipEntry.getName(), currentEntryBytes, entries);
                    continue;
                }

                entries.add(new BundleArchiveEntry(zipEntry.getName(), false));
                entryBytes.put(zipEntry.getName(), currentEntryBytes);
            }
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

                byte[] nestedEntryBytes = readCurrentZipEntry(nestedZip);
                if (nestedEntry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    expandNestedZip(nestedName, nestedEntryBytes, entries);
                    continue;
                }

                entries.add(new BundleArchiveEntry(nestedName, false));
                entryBytes.put(nestedName, nestedEntryBytes);
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
