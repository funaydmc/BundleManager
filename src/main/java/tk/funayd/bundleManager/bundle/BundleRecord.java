package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleRecord {

    private final String id;
    private final String bundleId;
    private final String packageKey;
    private final String sourceZipName;
    private final long installedAtEpochMillis;
    private final List<String> warnings;
    private final List<InstalledFile> installedFiles;
    private final List<ConfigMutation> configMutations;
    private final List<String> createdDirectories;

    public BundleRecord(
            String id,
            String bundleId,
            String packageKey,
            String sourceZipName,
            long installedAtEpochMillis,
            List<String> warnings,
            List<InstalledFile> installedFiles,
            List<ConfigMutation> configMutations,
            List<String> createdDirectories
    ) {
        this.id = id;
        this.bundleId = bundleId;
        this.packageKey = packageKey;
        this.sourceZipName = sourceZipName;
        this.installedAtEpochMillis = installedAtEpochMillis;
        this.warnings = List.copyOf(warnings);
        this.installedFiles = List.copyOf(installedFiles);
        this.configMutations = List.copyOf(configMutations);
        this.createdDirectories = List.copyOf(createdDirectories);
    }

    public String getId() {
        return id;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getPackageKey() {
        return packageKey;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public long getInstalledAtEpochMillis() {
        return installedAtEpochMillis;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<InstalledFile> getInstalledFiles() {
        return Collections.unmodifiableList(installedFiles);
    }

    public List<ConfigMutation> getConfigMutations() {
        return Collections.unmodifiableList(configMutations);
    }

    public List<String> getCreatedDirectories() {
        return Collections.unmodifiableList(createdDirectories);
    }

    public String getBundleShortId() {
        return bundleId;
    }

    public String getShortId() {
        return getBundleShortId() + "/" + packageKey;
    }

    public static final class InstalledFile {

        private final String sourceEntry;
        private final String pluginKey;
        private final String targetPath;
        private final String backupPath;

        public InstalledFile(String sourceEntry, String pluginKey, String targetPath, String backupPath) {
            this.sourceEntry = sourceEntry;
            this.pluginKey = pluginKey;
            this.targetPath = targetPath;
            this.backupPath = backupPath;
        }

        public String getSourceEntry() {
            return sourceEntry;
        }

        public String getPluginKey() {
            return pluginKey;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public String getBackupPath() {
            return backupPath;
        }
    }

    public static final class ConfigMutation {

        private final String type;
        private final String configPath;
        private final String targetPath;
        private final String value;

        public ConfigMutation(String type, String configPath, String targetPath, String value) {
            this.type = type;
            this.configPath = configPath;
            this.targetPath = targetPath;
            this.value = value;
        }

        public String getType() {
            return type;
        }

        public String getConfigPath() {
            return configPath;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public String getValue() {
            return value;
        }

        public String uniqueKey() {
            return type + "|" + configPath + "|" + targetPath + "|" + value;
        }
    }
}
