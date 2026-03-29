package tk.funayd.bundleManager.installer.mythicmobs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;
import tk.funayd.bundleManager.bundle.PathUtils;
import tk.funayd.bundleManager.installer.AbstractPluginInstaller;
import tk.funayd.bundleManager.installer.BundleFileReader;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class MythicMobsInstaller extends AbstractPluginInstaller {

    private static final Set<String> CONTENT_ROOTS = Set.of(
            "mobs",
            "skills",
            "items",
            "packs",
            "drops",
            "spawners",
            "randomspawns",
            "conditions",
            "mechanics",
            "options",
            "templates",
            "bossbars",
            "assets"
    );
    private static final Set<String> SINGLE_SKILL_KEYS = Set.of(
            "cooldown",
            "skills",
            "conditions",
            "targetconditions",
            "casterconditions",
            "triggerconditions",
            "variables",
            "delay",
            "repeat",
            "totem",
            "aura",
            "interruptconditions"
    );
    private static final Set<String> SINGLE_MOB_KEYS = Set.of(
            "type",
            "display",
            "health",
            "damage",
            "faction",
            "equipment",
            "drops",
            "skills",
            "options",
            "modules",
            "aitargetselectors",
            "aigoalselectors",
            "bossbar"
    );
    private static final Set<String> SINGLE_ITEM_KEYS = Set.of(
            "id",
            "display",
            "material",
            "lore",
            "skills",
            "options",
            "nbt",
            "attributes"
    );

    @Override
    public String getPluginKey() {
        return "MythicMobs";
    }

    @Override
    public Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException {
        if (isIgnoredDataFile(relativePath)) {
            return Optional.empty();
        }

        List<String> segments = pathSegments(relativePath);
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        if (segments.size() == 1) {
            String fileName = segments.get(0);
            // Root config tong cua MythicMobs de nguoi dung tu quan ly, khong copy de tranh ghi de.
            if ("config.yml".equalsIgnoreCase(fileName)) {
                return Optional.empty();
            }

            if (isYamlFileName(fileName)) {
                return Optional.of(new ResolvedBundleFile(relativePath, pluginPath(relativePath)));
            }
            return Optional.empty();
        }

        String rootDirectory = segments.get(0).toLowerCase(Locale.ROOT);
        if (!CONTENT_ROOTS.contains(rootDirectory)) {
            return Optional.empty();
        }

        if (!isYamlFileName(segments.get(segments.size() - 1))) {
            // Asset media can giu nguyen ten file de tranh dut lien ket noi bo.
            return Optional.of(new ResolvedBundleFile(relativePath, pluginPath(relativePath)));
        }

        // Giu nguyen id mob/skill trong file, chi doi ten file yaml de tranh trung bundle.
        String targetRelativePath = replaceFileName(relativePath, prefixFileName(fileName(relativePath), bundleIdShort));
        return Optional.of(new ResolvedBundleFile(relativePath, pluginPath(targetRelativePath)));
    }

    @Override
    public List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort) {
        return noMutations();
    }

    @Override
    public List<BundleInstallIdentity> collectIncomingIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            identities.addAll(readYamlIdentities(plannedFile, fileReader.readFile(plannedFile)));
        }
        return identities;
    }

    @Override
    public List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        Path pluginRoot = serverRoot.resolve("plugins/MythicMobs");
        if (!Files.isDirectory(pluginRoot)) {
            return List.of();
        }

        ArrayList<BundleInstallIdentity> identities = new ArrayList<>();
        try (var paths = Files.walk(pluginRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relativePath = pluginRoot.relativize(path).toString().replace('\\', '/');
                if (!shouldReadYamlIds(relativePath)) {
                    continue;
                }
                identities.addAll(readYamlIdentities(
                        "plugins/MythicMobs/" + relativePath,
                        Files.readAllBytes(path)
                ));
            }
        } catch (Exception ex) {
            if (ex instanceof BundleException bundleException) {
                throw bundleException;
            }
            throw new BundleException("Failed to scan existing MythicMobs ids.", ex);
        }
        return identities;
    }

    private List<BundleInstallIdentity> readYamlIdentities(ResolvedBundleFile plannedFile, byte[] content) throws BundleException {
        return readYamlIdentities(plannedFile.getSourceRelativePath(), content);
    }

    private List<BundleInstallIdentity> readYamlIdentities(String relativePath, byte[] content) throws BundleException {
        if (!shouldReadYamlIds(relativePath)) {
            return List.of();
        }

        String identityType = identityType(relativePath);
        YamlConfiguration yaml = loadYaml(content, relativePath);
        if (isSingleDefinitionFile(relativePath, identityType, yaml)) {
            return List.of(new BundleInstallIdentity(
                    identityType,
                    PathUtils.baseName(fileName(relativePath)),
                    relativePath
            ));
        }
        return rootKeyIdentities(identityType, yaml.getKeys(false), relativePath);
    }

    private boolean shouldReadYamlIds(String relativePath) throws BundleException {
        List<String> segments = contentSegments(relativePath);
        if (segments.isEmpty()) {
            return false;
        }

        if (segments.size() == 1) {
            String fileName = segments.get(0);
            return isYamlFileName(fileName) && !"config.yml".equalsIgnoreCase(fileName);
        }

        return isYamlFileName(segments.get(segments.size() - 1))
                && CONTENT_ROOTS.contains(segments.get(0).toLowerCase(Locale.ROOT));
    }

    private String identityType(String relativePath) throws BundleException {
        List<String> segments = contentSegments(relativePath);
        if (segments.size() <= 1) {
            return "mythic id";
        }

        // Packs co the long Mobs/Skills/Items ben trong, nen phai xac dinh namespace theo toan bo duong dan.
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i).toLowerCase(Locale.ROOT);
            switch (segment) {
                case "mobs":
                    return "mob id";
                case "skills":
                    return "skill id";
                case "items":
                    return "item id";
                default:
                    break;
            }
        }

        return "mythic id";
    }

    private List<String> contentSegments(String relativePath) throws BundleException {
        List<String> segments = pathSegments(relativePath);
        if (segments.size() >= 3
                && "plugins".equalsIgnoreCase(segments.get(0))
                && "mythicmobs".equalsIgnoreCase(segments.get(1))) {
            return segments.subList(2, segments.size());
        }
        return segments;
    }

    private boolean isSingleDefinitionFile(
            String relativePath,
            String identityType,
            YamlConfiguration yaml
    ) throws BundleException {
        Set<String> knownKeys = switch (identityType) {
            case "skill id" -> SINGLE_SKILL_KEYS;
            case "mob id" -> SINGLE_MOB_KEYS;
            case "item id" -> SINGLE_ITEM_KEYS;
            default -> Set.of();
        };
        if (knownKeys.isEmpty()) {
            return false;
        }

        boolean foundKnownDefinitionKey = false;
        boolean foundNonSectionRootValue = false;
        for (String key : yaml.getKeys(false)) {
            Object value = yaml.get(key);
            if (knownKeys.contains(key.toLowerCase(Locale.ROOT))) {
                foundKnownDefinitionKey = true;
            }
            if (!(value instanceof ConfigurationSection)) {
                foundNonSectionRootValue = true;
            }
        }
        return foundKnownDefinitionKey && foundNonSectionRootValue;
    }
}
