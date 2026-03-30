package tk.funayd.bundleManager.bundle;

import tk.funayd.bundleManager.installer.InstallerRegistry;
import tk.funayd.bundleManager.installer.SupportedPluginInstaller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

final class BundleArchiveInspector {

    private final InstallerRegistry installerRegistry;

    BundleArchiveInspector(InstallerRegistry installerRegistry) {
        this.installerRegistry = installerRegistry;
    }

    ArchivePackageInfo inspectArchivePackages(
            BundleArchive archive,
            Map<String, String> installedPluginNames
    ) throws BundleException {
        TreeSet<String> installable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> allPackages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<BundlePackageDescriptor> descriptors = discoverPackageDescriptors(archive, installedPluginNames);
        List<BundleVariantGroup> variantGroups = buildVariantGroups(descriptors);
        for (BundlePackageDescriptor packageDescriptor : descriptors) {
            allPackages.add(packageDescriptor.packageKey());
            if (packageDescriptor.supported()) {
                installable.add(packageDescriptor.packageKey());
            }
        }
        return new ArchivePackageInfo(
                descriptors,
                new ArrayList<>(installable),
                new ArrayList<>(allPackages),
                variantGroups,
                buildVariantChoiceGroups(variantGroups)
        );
    }

    List<BundlePackageDescriptor> discoverPackageDescriptors(
            BundleArchive archive,
            Map<String, String> installedPluginNames
    ) throws BundleException {
        ArchiveDirectoryNode root = buildDirectoryTree(archive.entries());
        ArrayList<DiscoveredPackageRoot> discoveredRoots = new ArrayList<>();
        discoverPackageRoots(root, discoveredRoots, installedPluginNames);
        return buildPackageDescriptors(discoveredRoots);
    }

    private ArchiveDirectoryNode buildDirectoryTree(List<BundleArchiveEntry> archiveEntries) throws BundleException {
        ArchiveDirectoryNode root = new ArchiveDirectoryNode("", "", List.of());
        for (BundleArchiveEntry archiveEntry : archiveEntries) {
            String normalizedPath = PathUtils.normalizeZipPath(archiveEntry.getName());
            List<String> segments = PathUtils.splitSegments(normalizedPath);
            addEntry(root, segments, archiveEntry.isDirectory(), normalizedPath);
        }
        return root;
    }

    private void addEntry(
            ArchiveDirectoryNode root,
            List<String> segments,
            boolean directory,
            String normalizedPath
    ) {
        ArchiveDirectoryNode current = root;
        int lastDirectoryIndex = directory ? segments.size() - 1 : segments.size() - 2;
        for (int index = 0; index <= lastDirectoryIndex; index++) {
            current = current.child(segments.get(index));
        }

        if (!directory) {
            current.files.put(segments.get(segments.size() - 1), new BundleArchiveEntry(normalizedPath, false));
        }
    }

    private void discoverPackageRoots(
            ArchiveDirectoryNode current,
            List<DiscoveredPackageRoot> discoveredRoots,
            Map<String, String> installedPluginNames
    ) throws BundleException {
        // Gap root resource pack truoc de khong dao sau vao pack con.
        if (containsPackMcmeta(current)) {
            discoveredRoots.add(new DiscoveredPackageRoot(
                    "ResourcePack",
                    current.path,
                    true,
                    collectEntries(current),
                    buildResourcePackVariantParts(current.segments)
            ));
            return;
        }

        if (!current.path.isBlank()) {
            PluginFolderInfo folderInfo = parsePluginFolderInfo(current.name);
            if (folderInfo != null) {
                Optional<SupportedPluginInstaller> installer = installerRegistry.find(folderInfo.pluginName());
                if (installer.isPresent()) {
                    discoveredRoots.add(new DiscoveredPackageRoot(
                            installer.get().getPluginKey(),
                            current.path,
                            true,
                            collectEntries(current),
                            buildPluginVariantParts(current.segments)
                    ));
                    return;
                }

                String installedPluginName = installedPluginNames.get(folderInfo.pluginName().toLowerCase(Locale.ROOT));
                if (installedPluginName != null) {
                    discoveredRoots.add(new DiscoveredPackageRoot(
                            installedPluginName,
                            current.path,
                            false,
                            collectEntries(current),
                            buildPluginVariantParts(current.segments)
                    ));
                    return;
                }
            }
        }

        ArrayList<ArchiveDirectoryNode> children = new ArrayList<>(current.children.values());
        children.sort(Comparator.comparing(node -> node.name, String.CASE_INSENSITIVE_ORDER));
        for (ArchiveDirectoryNode child : children) {
            discoverPackageRoots(child, discoveredRoots, installedPluginNames);
        }
    }

    private boolean containsPackMcmeta(ArchiveDirectoryNode directoryNode) {
        return directoryNode.files.keySet().stream().anyMatch(fileName -> "pack.mcmeta".equalsIgnoreCase(fileName));
    }

    private List<BundleArchiveEntry> collectEntries(ArchiveDirectoryNode packageRoot) {
        ArrayList<BundleArchiveEntry> collected = new ArrayList<>();
        collectEntries(packageRoot, collected);
        collected.sort(Comparator.comparing(BundleArchiveEntry::getName, String.CASE_INSENSITIVE_ORDER));
        return collected;
    }

    private void collectEntries(ArchiveDirectoryNode current, List<BundleArchiveEntry> collected) {
        collected.addAll(current.files.values());
        for (ArchiveDirectoryNode child : current.children.values()) {
            collectEntries(child, collected);
        }
    }

    private List<String> buildPluginVariantParts(List<String> pathSegments) {
        ArrayList<String> parts = new ArrayList<>();
        for (int index = 0; index < pathSegments.size(); index++) {
            PluginFolderInfo folderInfo = parsePluginFolderInfo(pathSegments.get(index));
            if (folderInfo == null) {
                continue;
            }

            if (index == pathSegments.size() - 1) {
                addRawVariantPart(parts, folderInfo.variantName());
            } else {
                addRawVariantPart(parts, folderInfo.pluginName());
                addRawVariantPart(parts, folderInfo.variantName());
            }
        }
        return parts;
    }

    private List<String> buildResourcePackVariantParts(List<String> pathSegments) {
        ArrayList<String> parts = new ArrayList<>();
        for (String pathSegment : pathSegments) {
            PluginFolderInfo folderInfo = parsePluginFolderInfo(pathSegment);
            if (folderInfo == null) {
                continue;
            }
            addRawVariantPart(parts, folderInfo.pluginName());
            addRawVariantPart(parts, folderInfo.variantName());
        }
        return parts;
    }

    private List<BundlePackageDescriptor> buildPackageDescriptors(List<DiscoveredPackageRoot> discoveredRoots) {
        LinkedHashMap<String, List<DiscoveredPackageRoot>> groupedByPlugin = new LinkedHashMap<>();
        for (DiscoveredPackageRoot discoveredRoot : discoveredRoots) {
            groupedByPlugin.computeIfAbsent(discoveredRoot.pluginKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(discoveredRoot);
        }

        ArrayList<BundlePackageDescriptor> descriptors = new ArrayList<>(discoveredRoots.size());
        for (List<DiscoveredPackageRoot> groupedRoots : groupedByPlugin.values()) {
            List<List<String>> packageKeyParts = selectPackageKeyParts(groupedRoots);
            boolean multipleVariants = groupedRoots.size() > 1;
            for (int index = 0; index < groupedRoots.size(); index++) {
                DiscoveredPackageRoot discoveredRoot = groupedRoots.get(index);
                List<String> normalizedVariantParts = packageKeyParts.get(index);
                String packageKey = multipleVariants
                        ? buildPackageKey(discoveredRoot.pluginKey(), normalizedVariantParts)
                        : discoveredRoot.pluginKey();
                descriptors.add(new BundlePackageDescriptor(
                        packageKey,
                        discoveredRoot.pluginKey(),
                        discoveredRoot.rootPath(),
                        discoveredRoot.supported(),
                        discoveredRoot.entries(),
                        discoveredRoot.rawVariantParts(),
                        normalizedVariantParts
                ));
            }
        }
        descriptors.sort(Comparator.comparing(BundlePackageDescriptor::packageKey, String.CASE_INSENSITIVE_ORDER));
        return descriptors;
    }

    private List<List<String>> selectPackageKeyParts(List<DiscoveredPackageRoot> groupedRoots) {
        List<List<String>> rawVariantParts = groupedRoots.stream()
                .map(DiscoveredPackageRoot::rawVariantParts)
                .toList();
        List<List<String>> reducedVariantParts = reduceVariantNames(rawVariantParts);
        ArrayList<List<String>> selectedParts = new ArrayList<>(groupedRoots.size());
        for (int index = 0; index < groupedRoots.size(); index++) {
            List<String> candidate = normalizeVariantParts(reducedVariantParts.get(index));
            if (candidate.isEmpty()) {
                candidate = normalizeVariantParts(rawVariantParts.get(index));
            }
            if (candidate.isEmpty()) {
                candidate = List.of("variant");
            }
            selectedParts.add(new ArrayList<>(candidate));
        }
        ensureUniqueVariantParts(groupedRoots, selectedParts);
        return selectedParts;
    }

    private void ensureUniqueVariantParts(
            List<DiscoveredPackageRoot> groupedRoots,
            List<List<String>> selectedParts
    ) {
        LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < selectedParts.size(); index++) {
            List<String> currentParts = selectedParts.get(index);
            String signature = normalizedSignature(currentParts);
            if (!seen.containsKey(signature)) {
                seen.put(signature, 1);
                continue;
            }

            ArrayList<String> adjusted = new ArrayList<>(currentParts);
            for (String fallbackPart : buildFallbackVariantParts(groupedRoots.get(index).rootPath())) {
                if (adjusted.stream().noneMatch(fallbackPart::equalsIgnoreCase)) {
                    adjusted.add(fallbackPart);
                }
                signature = normalizedSignature(adjusted);
                if (!seen.containsKey(signature)) {
                    break;
                }
            }

            int suffix = 2;
            while (seen.containsKey(signature)) {
                if (adjusted.isEmpty()) {
                    adjusted.add("variant");
                }
                if (adjusted.size() == currentParts.size()) {
                    adjusted.add(String.valueOf(suffix));
                } else {
                    adjusted.set(adjusted.size() - 1, adjusted.get(adjusted.size() - 1) + "_" + suffix);
                }
                signature = normalizedSignature(adjusted);
                suffix++;
            }

            selectedParts.set(index, adjusted);
            seen.put(signature, 1);
        }
    }

    private List<String> buildFallbackVariantParts(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return List.of();
        }

        try {
            List<String> segments = PathUtils.splitSegments(rootPath);
            ArrayList<String> normalized = new ArrayList<>();
            for (String segment : segments) {
                PluginFolderInfo folderInfo = parsePluginFolderInfo(segment);
                if (folderInfo == null) {
                    continue;
                }
                addVariantToken(normalized, folderInfo.pluginName());
                addVariantToken(normalized, folderInfo.variantName());
            }
            return normalized;
        } catch (BundleException ex) {
            return List.of();
        }
    }

    private String normalizedSignature(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        return String.join("+", parts).toLowerCase(Locale.ROOT);
    }

    private PluginFolderInfo parsePluginFolderInfo(String rawSegment) {
        if (rawSegment == null) {
            return null;
        }

        String segment = rawSegment.trim();
        if (segment.isEmpty()) {
            return null;
        }

        int openParen = segment.lastIndexOf('(');
        if (openParen > 0 && segment.endsWith(")")) {
            String pluginName = segment.substring(0, openParen).trim();
            String variantName = segment.substring(openParen + 1, segment.length() - 1).trim();
            if (!pluginName.isEmpty() && !variantName.isEmpty()) {
                return new PluginFolderInfo(pluginName, variantName);
            }
        }

        return new PluginFolderInfo(segment, null);
    }

    private List<BundleVariantGroup> buildVariantGroups(List<BundlePackageDescriptor> descriptors) {
        LinkedHashMap<String, List<BundlePackageDescriptor>> grouped = new LinkedHashMap<>();
        for (BundlePackageDescriptor descriptor : descriptors) {
            if (!descriptor.supported()) {
                continue;
            }
            grouped.computeIfAbsent(descriptor.pluginKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(descriptor);
        }

        ArrayList<BundleVariantGroup> groups = new ArrayList<>();
        for (List<BundlePackageDescriptor> pluginDescriptors : grouped.values()) {
            if (pluginDescriptors.size() <= 1) {
                continue;
            }

            List<List<String>> reducedParts = reduceVariantNames(pluginDescriptors.stream()
                    .map(BundlePackageDescriptor::rawVariantParts)
                    .toList());
            ArrayList<BundleVariantOption> options = new ArrayList<>(pluginDescriptors.size());
            for (int index = 0; index < pluginDescriptors.size(); index++) {
                BundlePackageDescriptor descriptor = pluginDescriptors.get(index);
                List<String> parts = reducedParts.get(index);
                String displayName = parts.isEmpty()
                        ? descriptor.packageKey()
                        : String.join(".", parts);
                options.add(new BundleVariantOption(descriptor.packageKey(), displayName));
            }
            options.sort(Comparator.comparing(BundleVariantOption::displayName, String.CASE_INSENSITIVE_ORDER));
            groups.add(new BundleVariantGroup(pluginDescriptors.get(0).pluginKey(), options));
        }
        groups.sort(Comparator.comparing(BundleVariantGroup::pluginKey, String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    private List<VariantChoiceGroup> buildVariantChoiceGroups(List<BundleVariantGroup> packageVariantGroups) {
        ArrayList<VariantChoiceGroup> choiceGroups = new ArrayList<>();

        VariantChoiceGroup bundleChoiceGroup = buildBundleChoiceGroup(packageVariantGroups);
        if (bundleChoiceGroup != null) {
            choiceGroups.add(bundleChoiceGroup);
        }

        for (BundleVariantGroup packageVariantGroup : packageVariantGroups) {
            if (packageVariantGroup.options().size() <= 1) {
                continue;
            }

            ArrayList<VariantChoiceOption> options = new ArrayList<>(packageVariantGroup.options().size());
            for (BundleVariantOption option : packageVariantGroup.options()) {
                LinkedHashMap<String, String> selections = new LinkedHashMap<>();
                selections.put(packageVariantGroup.pluginKey().toLowerCase(Locale.ROOT), option.packageKey());
                options.add(new VariantChoiceOption(option.displayName(), selections));
            }
            choiceGroups.add(new VariantChoiceGroup(packageVariantGroup.pluginKey(), options));
        }

        return choiceGroups;
    }

    private VariantChoiceGroup buildBundleChoiceGroup(List<BundleVariantGroup> packageVariantGroups) {
        if (packageVariantGroups.size() <= 1) {
            return null;
        }

        LinkedHashMap<String, BundleVariantAggregate> aggregated = new LinkedHashMap<>();
        for (BundleVariantGroup packageVariantGroup : packageVariantGroups) {
            for (BundleVariantOption option : packageVariantGroup.options()) {
                String normalizedName = option.displayName().toLowerCase(Locale.ROOT);
                BundleVariantAggregate aggregate = aggregated.computeIfAbsent(
                        normalizedName,
                        ignored -> new BundleVariantAggregate(option.displayName())
                );
                aggregate.selections.put(packageVariantGroup.pluginKey().toLowerCase(Locale.ROOT), option.packageKey());
            }
        }

        ArrayList<VariantChoiceOption> options = new ArrayList<>();
        for (BundleVariantAggregate aggregate : aggregated.values()) {
            if (aggregate.selections.size() <= 1) {
                continue;
            }
            options.add(new VariantChoiceOption(
                    aggregate.displayName,
                    new LinkedHashMap<>(aggregate.selections)
            ));
        }

        if (options.size() <= 1) {
            return null;
        }

        options.sort(Comparator.comparing(VariantChoiceOption::displayName, String.CASE_INSENSITIVE_ORDER));
        return new VariantChoiceGroup("Bundle", options);
    }

    private List<List<String>> reduceVariantNames(List<List<String>> rawParts) {
        ArrayList<List<String>> reduced = new ArrayList<>(rawParts.size());
        for (List<String> parts : rawParts) {
            reduced.add(new ArrayList<>(parts));
        }

        while (allShareCommonPrefix(reduced)) {
            for (List<String> parts : reduced) {
                parts.remove(0);
            }
        }

        while (allShareCommonSuffix(reduced)) {
            for (List<String> parts : reduced) {
                parts.remove(parts.size() - 1);
            }
        }

        for (int index = 0; index < reduced.size(); index++) {
            if (reduced.get(index).isEmpty()) {
                reduced.set(index, new ArrayList<>(rawParts.get(index)));
            }
        }
        return reduced;
    }

    private boolean allShareCommonPrefix(List<List<String>> values) {
        if (values.size() <= 1) {
            return false;
        }
        String prefix = null;
        for (List<String> parts : values) {
            if (parts.size() <= 1) {
                return false;
            }
            String current = parts.get(0);
            if (prefix == null) {
                prefix = current;
                continue;
            }
            if (!prefix.equalsIgnoreCase(current)) {
                return false;
            }
        }
        return prefix != null;
    }

    private boolean allShareCommonSuffix(List<List<String>> values) {
        if (values.size() <= 1) {
            return false;
        }
        String suffix = null;
        for (List<String> parts : values) {
            if (parts.size() <= 1) {
                return false;
            }
            String current = parts.get(parts.size() - 1);
            if (suffix == null) {
                suffix = current;
                continue;
            }
            if (!suffix.equalsIgnoreCase(current)) {
                return false;
            }
        }
        return suffix != null;
    }

    private List<String> normalizeVariantParts(List<String> rawParts) {
        ArrayList<String> normalized = new ArrayList<>();
        for (String rawPart : rawParts) {
            addVariantToken(normalized, rawPart);
        }
        return normalized;
    }

    private void addVariantToken(List<String> variants, String rawVariant) {
        if (rawVariant == null || rawVariant.isBlank()) {
            return;
        }

        String normalized = rawVariant.trim().replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && variants.stream().noneMatch(normalized::equalsIgnoreCase)) {
            variants.add(normalized);
        }
    }

    private void addRawVariantPart(List<String> variants, String rawVariant) {
        if (rawVariant == null || rawVariant.isBlank()) {
            return;
        }

        String normalized = rawVariant.trim();
        if (!normalized.isEmpty() && variants.stream().noneMatch(normalized::equalsIgnoreCase)) {
            variants.add(normalized);
        }
    }

    private String buildPackageKey(String pluginKey, List<String> variants) {
        if (variants == null || variants.isEmpty()) {
            return pluginKey;
        }
        return pluginKey + "@" + String.join("+", variants);
    }

    private static final class ArchiveDirectoryNode {
        private final String name;
        private final String path;
        private final List<String> segments;
        private final LinkedHashMap<String, ArchiveDirectoryNode> children;
        private final LinkedHashMap<String, BundleArchiveEntry> files;

        private ArchiveDirectoryNode(String name, String path, List<String> segments) {
            this.name = name;
            this.path = path;
            this.segments = segments;
            this.children = new LinkedHashMap<>();
            this.files = new LinkedHashMap<>();
        }

        private ArchiveDirectoryNode child(String childName) {
            return children.computeIfAbsent(childName.toLowerCase(Locale.ROOT), ignored -> {
                ArrayList<String> nextSegments = new ArrayList<>(segments);
                nextSegments.add(childName);
                String nextPath = path.isBlank() ? childName : path + "/" + childName;
                return new ArchiveDirectoryNode(childName, nextPath, List.copyOf(nextSegments));
            });
        }
    }

    private record DiscoveredPackageRoot(
            String pluginKey,
            String rootPath,
            boolean supported,
            List<BundleArchiveEntry> entries,
            List<String> rawVariantParts
    ) {
    }
}
