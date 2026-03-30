package tk.funayd.bundleManager.bundle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

final class BundleStateStore {

    private final File packageDirectory;
    private final File preferenceDirectory;
    private final File conflictDirectory;
    private final File bundleIndexFile;
    private final File archiveCompatibilityFile;
    private Map<String, ArchiveOpenPreference> archiveOpenPreferences;

    BundleStateStore(
            File packageDirectory,
            File preferenceDirectory,
            File conflictDirectory,
            File bundleIndexFile,
            File archiveCompatibilityFile
    ) {
        this.packageDirectory = packageDirectory;
        this.preferenceDirectory = preferenceDirectory;
        this.conflictDirectory = conflictDirectory;
        this.bundleIndexFile = bundleIndexFile;
        this.archiveCompatibilityFile = archiveCompatibilityFile;
        this.archiveOpenPreferences = null;
    }

    List<BundleRecord> listInstalledPackageRecords(Logger logger) {
        File[] files = packageDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<BundleRecord> records = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                records.add(loadRecord(file));
            } catch (BundleException ex) {
                logger.warning("Skipping broken record " + file.getName() + ": " + ex.getMessage());
            }
        }

        records.sort(Comparator.comparingLong(BundleRecord::getInstalledAtEpochMillis).reversed());
        return records;
    }

    BundlePreference loadPreference(String bundleId, String sourceZipName) {
        File file = getPreferenceFile(bundleId);
        if (!file.exists()) {
            return new BundlePreference(bundleId, sourceZipName, null, false, List.of(), null, Map.of());
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>();
        ConfigurationSection selectionSection = configuration.getConfigurationSection("selectedPackages");
        if (selectionSection != null) {
            for (String key : selectionSection.getKeys(false)) {
                String selectedPackage = selectionSection.getString(key);
                if (selectedPackage != null && !selectedPackage.isBlank()) {
                    selectedPackages.put(key.toLowerCase(Locale.ROOT), selectedPackage);
                }
            }
        }
        return new BundlePreference(
                configuration.getString("bundleId", bundleId),
                configuration.getString("sourceZipName", sourceZipName),
                configuration.getString("sourceSha1"),
                configuration.getBoolean("bundleDisabled", false),
                configuration.getStringList("disabledPackages"),
                configuration.getString("selectedBundleVariant"),
                selectedPackages
        );
    }

    List<BundlePreference> loadAllPreferences() {
        File[] files = preferenceDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        ArrayList<BundlePreference> preferences = new ArrayList<>(files.length);
        for (File file : files) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            preferences.add(new BundlePreference(
                    configuration.getString("bundleId", PathUtils.baseName(file.getName())),
                    configuration.getString("sourceZipName", PathUtils.baseName(file.getName())),
                    configuration.getString("sourceSha1"),
                    configuration.getBoolean("bundleDisabled", false),
                    configuration.getStringList("disabledPackages"),
                    configuration.getString("selectedBundleVariant"),
                    readSelectedPackages(configuration)
            ));
        }
        return preferences;
    }

    void savePreference(BundlePreference preference) throws BundleException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bundleId", preference.getBundleId());
        configuration.set("sourceZipName", preference.getSourceZipName());
        configuration.set("sourceSha1", preference.getSourceSha1());
        configuration.set("bundleDisabled", preference.isBundleDisabled());
        configuration.set("disabledPackages", new ArrayList<>(preference.getDisabledPackages()));
        configuration.set("selectedBundleVariant", preference.getSelectedBundleVariant());
        configuration.set("selectedPackages", new LinkedHashMap<>(preference.getSelectedPackages()));
        try {
            configuration.save(getPreferenceFile(preference.getBundleId()));
        } catch (IOException ex) {
            throw new BundleException("Failed to save bundle preference " + preference.getBundleId() + ".", ex);
        }
    }

    boolean preferenceExists(String bundleId) {
        return getPreferenceFile(bundleId).exists();
    }

    void deletePreference(String bundleId) throws BundleException {
        File file = getPreferenceFile(bundleId);
        if (!file.exists()) {
            return;
        }

        if (!file.delete()) {
            throw new BundleException("Failed to delete bundle preference " + bundleId + ".");
        }
    }

    List<BundleOverwriteConflict> listOverwriteConflicts() {
        File[] files = conflictDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        ArrayList<BundleOverwriteConflict> conflicts = new ArrayList<>(files.length);
        for (File file : files) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            conflicts.add(new BundleOverwriteConflict(
                    configuration.getString("id", PathUtils.baseName(file.getName())),
                    configuration.getString("bundleId", ""),
                    configuration.getString("sourceZipName", ""),
                    configuration.getString("packageKey", ""),
                    configuration.getStringList("targetPaths"),
                    configuration.getLong("createdAtEpochMillis", 0L)
            ));
        }
        conflicts.sort(Comparator.comparingLong(BundleOverwriteConflict::getCreatedAtEpochMillis));
        return conflicts;
    }

    BundleOverwriteConflict loadOverwriteConflict(String id) throws BundleException {
        File file = getConflictFile(id);
        if (!file.exists()) {
            throw new BundleException("Conflict not found: " + id);
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        return new BundleOverwriteConflict(
                configuration.getString("id", id),
                configuration.getString("bundleId", ""),
                configuration.getString("sourceZipName", ""),
                configuration.getString("packageKey", ""),
                configuration.getStringList("targetPaths"),
                configuration.getLong("createdAtEpochMillis", 0L)
        );
    }

    BundleOverwriteConflict saveOrUpdateOverwriteConflict(
            String bundleId,
            String sourceZipName,
            String packageKey,
            List<String> targetPaths
    ) throws BundleException {
        for (BundleOverwriteConflict existing : listOverwriteConflicts()) {
            if (existing.getBundleId().equalsIgnoreCase(bundleId)
                    && existing.getPackageKey().equalsIgnoreCase(packageKey)) {
                BundleOverwriteConflict updated = new BundleOverwriteConflict(
                        existing.getId(),
                        bundleId,
                        sourceZipName,
                        packageKey,
                        targetPaths,
                        existing.getCreatedAtEpochMillis()
                );
                saveOverwriteConflict(updated);
                return updated;
            }
        }

        int nextId = 1;
        for (BundleOverwriteConflict existing : listOverwriteConflicts()) {
            try {
                nextId = Math.max(nextId, Integer.parseInt(existing.getId()) + 1);
            } catch (NumberFormatException ignored) {
            }
        }

        BundleOverwriteConflict created = new BundleOverwriteConflict(
                String.valueOf(nextId),
                bundleId,
                sourceZipName,
                packageKey,
                targetPaths,
                System.currentTimeMillis()
        );
        saveOverwriteConflict(created);
        return created;
    }

    void deleteOverwriteConflict(String id) {
        getConflictFile(id).delete();
    }

    void deleteOverwriteConflict(String bundleId, String packageKey) {
        for (BundleOverwriteConflict conflict : listOverwriteConflicts()) {
            if (conflict.getBundleId().equalsIgnoreCase(bundleId)
                    && conflict.getPackageKey().equalsIgnoreCase(packageKey)) {
                deleteOverwriteConflict(conflict.getId());
            }
        }
    }

    void saveRecord(BundleRecord record) throws BundleException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", record.getId());
        config.set("bundleId", record.getBundleId());
        config.set("packageKey", record.getPackageKey());
        config.set("sourceZipName", record.getSourceZipName());
        config.set("installedAtEpochMillis", record.getInstalledAtEpochMillis());
        config.set("warnings", new ArrayList<>(record.getWarnings()));
        config.set("createdDirectories", new ArrayList<>(record.getCreatedDirectories()));

        List<Map<String, Object>> fileMaps = new ArrayList<>();
        for (BundleRecord.InstalledFile installedFile : record.getInstalledFiles()) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("sourceEntry", installedFile.getSourceEntry());
            values.put("pluginKey", installedFile.getPluginKey());
            values.put("targetPath", installedFile.getTargetPath());
            values.put("backupPath", installedFile.getBackupPath());
            fileMaps.add(values);
        }
        config.set("installedFiles", fileMaps);

        List<Map<String, Object>> mutationMaps = new ArrayList<>();
        for (BundleRecord.ConfigMutation mutation : record.getConfigMutations()) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("type", mutation.getType());
            values.put("configPath", mutation.getConfigPath());
            values.put("targetPath", mutation.getTargetPath());
            values.put("value", mutation.getValue());
            mutationMaps.add(values);
        }
        config.set("configMutations", mutationMaps);

        try {
            config.save(getRecordFile(record.getId()));
        } catch (IOException ex) {
            throw new BundleException("Failed to save bundle record " + record.getId() + ".", ex);
        }
    }

    boolean recordExists(String recordId) {
        return getRecordFile(recordId).exists();
    }

    File getRecordFile(String recordId) {
        return new File(packageDirectory, recordId + ".yml");
    }

    String computeBundleId(File source, Logger logger) throws BundleException {
        return allocateBundleId(source.getName(), logger);
    }

    boolean shouldPreferStreamingZip(File sourceFile) {
        ArchiveOpenPreference preference = loadArchiveOpenPreferences().get(pathKey(sourceFile));
        return preference != null && preference.matches(sourceFile);
    }

    void rememberStreamingZip(File sourceFile) throws BundleException {
        Map<String, ArchiveOpenPreference> preferences = loadArchiveOpenPreferences();
        String pathKey = pathKey(sourceFile);
        ArchiveOpenPreference updated = ArchiveOpenPreference.forFile(sourceFile);
        ArchiveOpenPreference existing = preferences.get(pathKey);
        if (updated.equals(existing)) {
            return;
        }

        preferences.put(pathKey, updated);
        saveArchiveOpenPreferences(preferences);
    }

    void clearArchiveOpenPreference(File sourceFile) throws BundleException {
        Map<String, ArchiveOpenPreference> preferences = loadArchiveOpenPreferences();
        if (preferences.remove(pathKey(sourceFile)) != null) {
            saveArchiveOpenPreferences(preferences);
        }
    }

    private Map<String, String> readSelectedPackages(YamlConfiguration configuration) {
        LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>();
        ConfigurationSection selectionSection = configuration.getConfigurationSection("selectedPackages");
        if (selectionSection == null) {
            return selectedPackages;
        }

        for (String key : selectionSection.getKeys(false)) {
            String selectedPackage = selectionSection.getString(key);
            if (selectedPackage != null && !selectedPackage.isBlank()) {
                selectedPackages.put(key.toLowerCase(Locale.ROOT), selectedPackage);
            }
        }
        return selectedPackages;
    }

    private BundleRecord loadRecord(File file) throws BundleException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String id = requireString(config, "id", file);
        String bundleId = requireString(config, "bundleId", file);
        String packageKey = requireString(config, "packageKey", file);
        String sourceZipName = requireString(config, "sourceZipName", file);
        long installedAt = config.getLong("installedAtEpochMillis", 0L);
        List<String> warnings = config.getStringList("warnings");
        List<String> createdDirectories = config.getStringList("createdDirectories");

        List<BundleRecord.InstalledFile> installedFiles = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("installedFiles")) {
            Object pluginKey = raw.containsKey("pluginKey") ? raw.get("pluginKey") : raw.get("packageKey");
            installedFiles.add(new BundleRecord.InstalledFile(
                    stringValue(raw.get("sourceEntry")),
                    stringValue(pluginKey),
                    stringValue(raw.get("targetPath")),
                    stringValue(raw.get("backupPath"))
            ));
        }

        List<BundleRecord.ConfigMutation> configMutations = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("configMutations")) {
            configMutations.add(new BundleRecord.ConfigMutation(
                    stringValue(raw.get("type")),
                    stringValue(raw.get("configPath")),
                    stringValue(raw.get("targetPath")),
                    stringValue(raw.get("value"))
            ));
        }

        return new BundleRecord(
                id,
                bundleId,
                packageKey,
                sourceZipName,
                installedAt,
                warnings,
                installedFiles,
                configMutations,
                createdDirectories
        );
    }

    private String allocateBundleId(String sourceName, Logger logger) throws BundleException {
        YamlConfiguration index = YamlConfiguration.loadConfiguration(bundleIndexFile);
        String sourceKey = normalizedSourceKey(sourceName);

        String existingId = index.getString("entries." + sourceKey);
        if (existingId != null && !existingId.isBlank()) {
            return existingId;
        }

        String migratedId = findExistingBundleIdForSource(sourceName, logger);
        if (migratedId != null && !migratedId.isBlank()) {
            index.set("entries." + sourceKey, migratedId);
            saveBundleIndex(index);
            return migratedId;
        }

        int nextId = Math.max(1, index.getInt("nextId", 1));
        String bundleId = String.valueOf(nextId);
        index.set("entries." + sourceKey, bundleId);
        index.set("nextId", nextId + 1);
        saveBundleIndex(index);
        return bundleId;
    }

    private void saveBundleIndex(YamlConfiguration index) throws BundleException {
        try {
            index.save(bundleIndexFile);
        } catch (IOException ex) {
            throw new BundleException("Failed to save bundle index.", ex);
        }
    }

    private String findExistingBundleIdForSource(String sourceName, Logger logger) {
        for (BundleRecord record : listInstalledPackageRecords(logger)) {
            if (record.getSourceZipName().equalsIgnoreCase(sourceName)) {
                return record.getBundleId();
            }
        }
        for (BundlePreference preference : loadAllPreferences()) {
            if (preference.getSourceZipName().equalsIgnoreCase(sourceName)) {
                return preference.getBundleId();
            }
        }
        return null;
    }

    private String normalizedSourceKey(String sourceName) {
        return sourceName.trim().toLowerCase(Locale.ROOT).replace('.', '_');
    }

    private Map<String, ArchiveOpenPreference> loadArchiveOpenPreferences() {
        if (archiveOpenPreferences != null) {
            return archiveOpenPreferences;
        }

        LinkedHashMap<String, ArchiveOpenPreference> preferences = new LinkedHashMap<>();
        if (archiveCompatibilityFile.exists()) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(archiveCompatibilityFile);
            for (Map<?, ?> raw : configuration.getMapList("entries")) {
                ArchiveOpenPreference preference = ArchiveOpenPreference.fromMap(raw);
                if (preference != null) {
                    preferences.put(preference.pathKey(), preference);
                }
            }
        }

        archiveOpenPreferences = preferences;
        return archiveOpenPreferences;
    }

    private void saveArchiveOpenPreferences(Map<String, ArchiveOpenPreference> preferences) throws BundleException {
        YamlConfiguration configuration = new YamlConfiguration();
        List<Map<String, Object>> serialized = new ArrayList<>(preferences.size());
        for (ArchiveOpenPreference preference : preferences.values()) {
            serialized.add(preference.toMap());
        }
        configuration.set("entries", serialized);
        try {
            configuration.save(archiveCompatibilityFile);
        } catch (IOException ex) {
            throw new BundleException("Failed to save archive compatibility cache.", ex);
        }
    }

    private String pathKey(File sourceFile) {
        return sourceFile.getAbsoluteFile().toPath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private String requireString(YamlConfiguration config, String path, File sourceFile) throws BundleException {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new BundleException("Missing '" + path + "' in record " + sourceFile.getName() + ".");
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private File getPreferenceFile(String bundleId) {
        return new File(preferenceDirectory, bundleId + ".yml");
    }

    private File getConflictFile(String id) {
        return new File(conflictDirectory, id + ".yml");
    }

    private void saveOverwriteConflict(BundleOverwriteConflict conflict) throws BundleException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("id", conflict.getId());
        configuration.set("bundleId", conflict.getBundleId());
        configuration.set("sourceZipName", conflict.getSourceZipName());
        configuration.set("packageKey", conflict.getPackageKey());
        configuration.set("targetPaths", new ArrayList<>(conflict.getTargetPaths()));
        configuration.set("createdAtEpochMillis", conflict.getCreatedAtEpochMillis());
        try {
            configuration.save(getConflictFile(conflict.getId()));
        } catch (IOException ex) {
            throw new BundleException("Failed to save overwrite conflict " + conflict.getId() + ".", ex);
        }
    }

    private record ArchiveOpenPreference(String path, long size, long lastModified) {

        static ArchiveOpenPreference forFile(File sourceFile) {
            File absoluteFile = sourceFile.getAbsoluteFile();
            return new ArchiveOpenPreference(
                    absoluteFile.toPath().normalize().toString(),
                    absoluteFile.length(),
                    absoluteFile.lastModified()
            );
        }

        static ArchiveOpenPreference fromMap(Map<?, ?> raw) {
            Object path = raw.get("path");
            if (path == null || String.valueOf(path).isBlank()) {
                return null;
            }

            return new ArchiveOpenPreference(
                    String.valueOf(path),
                    longValue(raw.get("size")),
                    longValue(raw.get("lastModified"))
            );
        }

        boolean matches(File sourceFile) {
            File absoluteFile = sourceFile.getAbsoluteFile();
            return path.equals(absoluteFile.toPath().normalize().toString())
                    && size == absoluteFile.length()
                    && lastModified == absoluteFile.lastModified();
        }

        String pathKey() {
            return path.toLowerCase(Locale.ROOT);
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("path", path);
            values.put("size", size);
            values.put("lastModified", lastModified);
            return values;
        }

        private static long longValue(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value == null) {
                return 0L;
            }
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}
