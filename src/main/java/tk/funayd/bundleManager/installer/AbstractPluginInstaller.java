package tk.funayd.bundleManager.installer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.bundle.GlobPattern;
import tk.funayd.bundleManager.bundle.PathUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

public abstract class AbstractPluginInstaller implements SupportedPluginInstaller {

    private static final List<GlobPattern> COMMON_IGNORED_FILES = List.of(
            new GlobPattern("**/.DS_Store"),
            new GlobPattern("**/Thumbs.db"),
            new GlobPattern("**/__MACOSX/**")
    );

    protected final boolean isIgnoredDataFile(String relativePath) {
        // Bo qua cac file rac ma bundle hay dinh kem theo.
        return COMMON_IGNORED_FILES.stream().anyMatch(pattern -> pattern.matches(relativePath));
    }

    protected final String pluginPath(String pathUnderPlugin) throws BundleException {
        return PathUtils.normalizeRelativePath("plugins/" + getPluginKey() + "/" + pathUnderPlugin);
    }

    protected final String prefixFileName(String fileName, String bundleIdShort) {
        return bundleIdShort + "_" + fileName;
    }

    protected final boolean isYamlFileName(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".yml") || lowerName.endsWith(".yaml");
    }

    protected final List<String> pathSegments(String relativePath) throws BundleException {
        return PathUtils.splitSegments(relativePath);
    }

    protected final String fileName(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        return segments.get(segments.size() - 1);
    }

    protected final String fileBaseName(String relativePath) throws BundleException {
        return PathUtils.baseName(fileName(relativePath));
    }

    protected final String replaceFileName(String relativePath, String newFileName) throws BundleException {
        List<String> segments = new ArrayList<>(pathSegments(relativePath));
        segments.set(segments.size() - 1, newFileName);
        return PathUtils.normalizeRelativePath(String.join("/", segments));
    }

    protected final ResolvedBundleFile renameTargetFileOnConflict(
            ResolvedBundleFile bundleFile,
            String bundleIdShort
    ) throws BundleException {
        String targetRelativePath = bundleFile.getTargetRelativePath();
        String renamedTargetPath = replaceFileName(
                targetRelativePath,
                prefixFileName(fileName(targetRelativePath), bundleIdShort)
        );
        return bundleFile.withTargetRelativePath(renamedTargetPath);
    }

    protected final BundleRecord.ConfigMutation appendStringListMutation(
            String configPath,
            String targetPath,
            String value
    ) {
        // Chi append gia tri moi, khong thay the danh sach co san.
        return new BundleRecord.ConfigMutation("APPEND_STRING_LIST", configPath, targetPath, value);
    }

    protected final BundleRecord.ConfigMutation registerSectionFileMutation(
            String configPath,
            String targetPath,
            String fileName
    ) {
        // Chi tao node dang ky moi neu path nay chua co.
        return new BundleRecord.ConfigMutation("REGISTER_SECTION_FILE", configPath, targetPath, fileName);
    }

    protected final List<BundleRecord.ConfigMutation> noMutations() {
        return List.of();
    }

    protected final List<BundleRecord.ConfigMutation> mutableMutationList() {
        return new ArrayList<>();
    }

    protected final byte[] rewriteYamlStrings(
            byte[] originalContent,
            UnaryOperator<String> stringRewriter
    ) throws BundleException {
        YamlConfiguration configuration = loadYaml(originalContent, "bundle content");

        boolean changed = rewriteConfigurationSection(configuration, stringRewriter);
        return changed
                ? configuration.saveToString().getBytes(StandardCharsets.UTF_8)
                : originalContent;
    }

    protected final YamlConfiguration loadYaml(byte[] content, String sourceDescription) throws BundleException {
        String yamlText = new String(content, StandardCharsets.UTF_8);
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yamlText);
        } catch (InvalidConfigurationException ex) {
            throw new BundleException("Failed to parse YAML from " + sourceDescription + ".", ex);
        }
        return configuration;
    }

    protected final YamlConfiguration loadYaml(Path file) throws BundleException {
        try {
            return loadYaml(Files.readAllBytes(file), file.toString());
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to read YAML file " + file + ".", ex);
        }
    }

    protected final List<BundleInstallIdentity> collectYamlRootIdentities(
            String type,
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            if (!isYamlFileName(fileName(plannedFile.getSourceRelativePath()))) {
                continue;
            }

            YamlConfiguration yaml = loadYaml(fileReader.readFile(plannedFile), plannedFile.getSourceEntryName());
            identities.addAll(rootKeyIdentities(type, yaml.getKeys(false), plannedFile.getSourceRelativePath()));
        }
        return identities;
    }

    protected final List<BundleInstallIdentity> collectYamlRootIdentities(
            String type,
            Path rootDirectory
    ) throws BundleException {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        if (!Files.isDirectory(rootDirectory)) {
            return identities;
        }

        try (var paths = Files.walk(rootDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!isYamlFileName(path.getFileName().toString())) {
                    continue;
                }

                YamlConfiguration yaml = loadYaml(path);
                identities.addAll(rootKeyIdentities(type, yaml.getKeys(false), rootDirectory.relativize(path).toString()));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan YAML files in " + rootDirectory + ".", ex);
        }
        return identities;
    }

    protected final List<BundleInstallIdentity> rootKeyIdentities(
            String type,
            Collection<String> ids,
            String source
    ) {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            identities.add(new BundleInstallIdentity(type, id, source));
        }
        return identities;
    }

    private boolean rewriteConfigurationSection(
            ConfigurationSection section,
            UnaryOperator<String> stringRewriter
    ) {
        boolean changed = false;
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection childSection) {
                if (rewriteConfigurationSection(childSection, stringRewriter)) {
                    changed = true;
                }
                continue;
            }

            RewriteResult rewritten = rewriteValue(value, stringRewriter);
            if (rewritten.changed()) {
                section.set(key, rewritten.value());
                changed = true;
            }
        }
        return changed;
    }

    private RewriteResult rewriteValue(Object value, UnaryOperator<String> stringRewriter) {
        if (value instanceof String stringValue) {
            String rewritten = stringRewriter.apply(stringValue);
            return new RewriteResult(rewritten, !Objects.equals(stringValue, rewritten));
        }

        if (value instanceof List<?> listValue) {
            ArrayList<Object> rewrittenList = new ArrayList<>(listValue.size());
            boolean changed = false;
            for (Object entry : listValue) {
                RewriteResult rewrittenEntry = rewriteValue(entry, stringRewriter);
                rewrittenList.add(rewrittenEntry.value());
                changed |= rewrittenEntry.changed();
            }
            return new RewriteResult(rewrittenList, changed);
        }

        if (value instanceof Map<?, ?> mapValue) {
            LinkedHashMap<Object, Object> rewrittenMap = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                RewriteResult rewrittenEntry = rewriteValue(entry.getValue(), stringRewriter);
                rewrittenMap.put(entry.getKey(), rewrittenEntry.value());
                changed |= rewrittenEntry.changed();
            }
            return new RewriteResult(rewrittenMap, changed);
        }

        return new RewriteResult(value, false);
    }

    private record RewriteResult(Object value, boolean changed) {
    }
}
