package tk.funayd.bundleManager.bundle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import tk.funayd.bundleManager.installer.BundleInstallIdentity;
import tk.funayd.bundleManager.installer.InstallerRegistry;
import tk.funayd.bundleManager.installer.ReferenceRewriteContext;
import tk.funayd.bundleManager.installer.ResolvedBundleFile;
import tk.funayd.bundleManager.installer.SupportedPluginInstaller;
import tk.funayd.bundleManager.installer.blueprints.BlueprintsInstaller;
import tk.funayd.bundleManager.installer.deluxemenus.DeluxeMenusInstaller;
import tk.funayd.bundleManager.installer.itemsadder.ItemsAdderInstaller;
import tk.funayd.bundleManager.installer.mcpets.MCPetsInstaller;
import tk.funayd.bundleManager.installer.mmoitems.MMOItemsInstaller;
import tk.funayd.bundleManager.installer.modelengine.ModelEngineInstaller;
import tk.funayd.bundleManager.installer.nexo.NexoInstaller;
import tk.funayd.bundleManager.installer.mythiclib.MythicLibInstaller;
import tk.funayd.bundleManager.installer.mythicmobs.MythicMobsInstaller;
import tk.funayd.bundleManager.installer.oraxen.OraxenInstaller;
import tk.funayd.bundleManager.installer.resourcepack.ResourcePackInstaller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

public final class BundleService {

    private static final String APPEND_STRING_LIST = "APPEND_STRING_LIST";
    private static final String REGISTER_SECTION_FILE = "REGISTER_SECTION_FILE";
    private static final String ISSUE_URL = "https://github.com/funaydmc/BundleManager/issues";

    private final JavaPlugin plugin;
    private final InstallerRegistry installerRegistry;
    private final BundleArchiveInspector archiveInspector;
    private final BundleStateStore stateStore;
    private final File incomingBundleDirectory;
    private final File dataDirectory;
    private final File packageDirectory;
    private final File preferenceDirectory;
    private final File conflictDirectory;
    private final File backupDirectory;
    private final File bundleIndexFile;
    private final Path serverRoot;
    private final Map<String, Map<String, String>> failedPackageMessages;
    private List<VariantPromptOption> pendingVariantOptions;
    private List<String> pendingVariantMessages;

    public BundleService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.installerRegistry = new InstallerRegistry(List.of(
                new ResourcePackInstaller(),
                new BlueprintsInstaller(),
                new MythicMobsInstaller(),
                new MythicLibInstaller(),
                new MMOItemsInstaller(),
                new ModelEngineInstaller(),
                new MCPetsInstaller(),
                new OraxenInstaller(),
                new NexoInstaller(),
                new ItemsAdderInstaller(),
                new DeluxeMenusInstaller()
        ));
        this.incomingBundleDirectory = new File(plugin.getDataFolder(), "bundles");
        this.dataDirectory = new File(plugin.getDataFolder(), "data");
        this.packageDirectory = new File(dataDirectory, "packages");
        this.preferenceDirectory = new File(dataDirectory, "preferences");
        this.conflictDirectory = new File(dataDirectory, "conflicts");
        this.backupDirectory = new File(dataDirectory, "backups");
        this.bundleIndexFile = new File(dataDirectory, "bundle-index.yml");
        this.archiveInspector = new BundleArchiveInspector(installerRegistry);
        this.stateStore = new BundleStateStore(packageDirectory, preferenceDirectory, conflictDirectory, bundleIndexFile);
        this.failedPackageMessages = new LinkedHashMap<>();
        this.pendingVariantOptions = List.of();
        this.pendingVariantMessages = List.of();

        this.serverRoot = deriveServerRoot(plugin.getDataFolder());
    }

    public void initialize() {
        createDirectory(plugin.getDataFolder().toPath());
        createDirectory(incomingBundleDirectory.toPath());
        createDirectory(dataDirectory.toPath());
        createDirectory(packageDirectory.toPath());
        createDirectory(preferenceDirectory.toPath());
        createDirectory(conflictDirectory.toPath());
        createDirectory(backupDirectory.toPath());
    }

    public File getIncomingBundleDirectory() {
        return incomingBundleDirectory;
    }

    public File getPersistentDataDirectory() {
        return dataDirectory;
    }

    public List<String> listSupportedPlugins() {
        TreeSet<String> supported = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        supported.addAll(installerRegistry.listSupportedPluginKeys());
        return new ArrayList<>(supported);
    }

    public List<String> listInstallablePackages(String archiveFileName) {
        try {
            File archiveFile = resolveBundleArchiveFile(archiveFileName);
            return inspectArchivePackages(archiveFile).installablePackages();
        } catch (BundleException | IOException ex) {
            return List.of();
        }
    }

    public List<String> listInstalledBundleQueries() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (BundleSummary bundle : listInstalledBundles()) {
            values.add(bundle.getBundleShortId());
            values.add(baseName(bundle.getSourceZipName()));
            values.add(bundle.getSourceZipName());
        }
        return new ArrayList<>(values);
    }

    public List<String> listInstalledPackageKeys(String bundleQuery) {
        TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BundleRecord record : findBundleRecords(bundleQuery)) {
            values.add(record.getPackageKey());
        }
        return new ArrayList<>(values);
    }

    public List<String> listKnownBundleIds() {
        TreeSet<String> bundleIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors(false)) {
            bundleIds.add(shortId(descriptor.bundleId()));
        }
        for (BundleSummary bundle : listInstalledBundles()) {
            bundleIds.add(bundle.getBundleShortId());
        }
        for (BundlePreference preference : loadAllPreferences()) {
            bundleIds.add(shortId(preference.getBundleId()));
        }
        return new ArrayList<>(bundleIds);
    }

    public List<String> listKnownPackageKeys(String bundleIdQuery) {
        try {
            KnownBundle knownBundle = resolveKnownBundle(bundleIdQuery);
            return listCommandPackageKeys(knownBundle);
        } catch (BundleException ex) {
            return List.of();
        }
    }

    public List<BundleOverwriteConflict> listOverwriteConflicts() {
        return stateStore.listOverwriteConflicts();
    }

    public List<String> listOverwriteConflictIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (BundleOverwriteConflict conflict : listOverwriteConflicts()) {
            ids.add(conflict.getId());
        }
        return ids;
    }

    public BundleActionReport resolveOverwriteConflict(String conflictId, boolean overwrite) throws BundleException {
        BundleOverwriteConflict conflict = stateStore.loadOverwriteConflict(conflictId);
        if (!overwrite) {
            stateStore.deleteOverwriteConflict(conflictId);
            disableBundleById(conflict.getBundleId(), conflict.getPackageKey());
            return new BundleActionReport(
                    conflict.getBundleId(),
                    conflict.getSourceZipName(),
                    List.of(),
                    List.of(),
                    List.of(conflict.getPackageKey()),
                    List.of("Skipped overwrite conflict " + conflictId + " and disabled package " + conflict.getPackageKey() + ".")
            );
        }

        List<BundleInstallResult> results = installBundle(
                conflict.getSourceZipName(),
                conflict.getPackageKey(),
                new LinkedHashSet<>(conflict.getTargetPaths())
        );
        stateStore.deleteOverwriteConflict(conflictId);
        ArrayList<String> warnings = new ArrayList<>();
        ArrayList<String> succeededPackages = new ArrayList<>();
        for (BundleInstallResult result : results) {
            succeededPackages.add(result.getRecord().getPackageKey());
            warnings.addAll(result.getWarnings());
        }
        warnings.add("Resolved overwrite conflict " + conflictId + " with overwrite approval.");
        return new BundleActionReport(
                conflict.getBundleId(),
                conflict.getSourceZipName(),
                succeededPackages,
                List.of(),
                List.of(),
                deduplicatePackages(warnings)
        );
    }

    public BundleLoadReport autoLoadBundles() {
        failedPackageMessages.clear();
        clearPendingVariantPrompt();
        int installedPackageCount = 0;
        int installedBundleCount = 0;
        ArrayList<MissingPluginRequirement> missingPlugins = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        List<BundleArchiveDescriptor> descriptors = scanIncomingBundleDescriptors(true);
        LinkedHashMap<String, BundleArchiveDescriptor> descriptorById = new LinkedHashMap<>();
        for (BundleArchiveDescriptor descriptor : descriptors) {
            descriptorById.put(descriptor.bundleId().toLowerCase(Locale.ROOT), descriptor);
        }

        for (BundleSummary installedBundle : listInstalledBundles()) {
            if (descriptorById.containsKey(installedBundle.getBundleId().toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                List<BundleRecord> removedRecords = uninstallBundle(installedBundle.getBundleId(), null);
                clearBundleFailures(installedBundle.getBundleId());
                if (!removedRecords.isEmpty()) {
                    warnings.add("Removed bundle " + installedBundle.getSourceZipName()
                            + " because it no longer exists in bundles folder.");
                }
            } catch (BundleException ex) {
                errors.add("Failed to remove missing bundle " + installedBundle.getSourceZipName() + ": " + ex.getMessage());
            }
        }

        for (BundleArchiveDescriptor descriptor : descriptors) {
            missingPlugins.addAll(buildMissingPluginRequirements(descriptor));
            warnings.addAll(descriptor.scanWarnings());
            try {
                BundleActionReport report = reconcileBundle(descriptor);
                int activePackageCount = listInstalledPackageKeys(descriptor.bundleId()).size();
                if (activePackageCount > 0) {
                    installedPackageCount += activePackageCount;
                    installedBundleCount++;
                }
                warnings.addAll(filterNonMissingPluginMessages(report.getMessages()));
            } catch (BundleException ex) {
                recordBundleFailure(descriptor.bundleId(), "__bundle__", ex.getMessage());
                int activePackageCount = listInstalledPackageKeys(descriptor.bundleId()).size();
                if (activePackageCount > 0) {
                    installedPackageCount += activePackageCount;
                    installedBundleCount++;
                }
                errors.add("Failed to auto-load bundle " + descriptor.sourceZipName() + ": " + ex.getMessage());
            }
        }
        return new BundleLoadReport(
                installedPackageCount,
                installedBundleCount,
                mergeMissingPluginRequirements(missingPlugins),
                deduplicatePackages(warnings),
                deduplicatePackages(errors)
        );
    }

    public boolean hasIgnoredIncomingFiles() {
        File[] files = incomingBundleDirectory.listFiles(File::isFile);
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (!isSupportedArchiveName(file.getName())) {
                return true;
            }
        }
        return false;
    }

    public List<String> getPendingVariantMessages() {
        return pendingVariantMessages;
    }

    public List<String> openVariantPrompt(String bundleIdQuery) throws BundleException {
        KnownBundle knownBundle = resolveKnownBundle(bundleIdQuery);
        if (knownBundle.archiveDescriptor() == null) {
            throw new BundleException("Bundle archive is not available for variant selection: " + bundleIdQuery);
        }

        ArrayList<VariantPromptOption> promptOptions = new ArrayList<>();
        ArrayList<String> promptMessages = new ArrayList<>();
        appendVariantPrompt(knownBundle.archiveDescriptor(), promptOptions, promptMessages);
        if (promptOptions.isEmpty()) {
            clearPendingVariantPrompt();
            throw new BundleException("Bundle does not have multiple variants: " + knownBundle.sourceZipName());
        }

        pendingVariantOptions = List.copyOf(promptOptions);
        pendingVariantMessages = List.copyOf(promptMessages);
        return pendingVariantMessages;
    }

    public List<String> listPendingVariantIndexes() {
        ArrayList<String> indexes = new ArrayList<>(pendingVariantOptions.size());
        for (VariantPromptOption option : pendingVariantOptions) {
            indexes.add(String.valueOf(option.index()));
        }
        return indexes;
    }

    public BundleActionReport switchVariant(int index) throws BundleException {
        VariantPromptOption option = pendingVariantOptions.stream()
                .filter(candidate -> candidate.index() == index)
                .findFirst()
                .orElseThrow(() -> new BundleException("Variant index not found: " + index));

        KnownBundle knownBundle = resolveKnownBundle(option.bundleId());
        if (knownBundle.archiveDescriptor() == null) {
            throw new BundleException("Bundle archive is not available for variant switch: " + option.bundleId());
        }
        BundlePreference originalPreference = loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName());
        LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>(originalPreference.getSelectedPackages());
        String selectedBundleVariant = originalPreference.getSelectedBundleVariant();
        if ("Bundle".equalsIgnoreCase(option.title())) {
            selectedBundleVariant = option.displayName();
        } else {
            for (Map.Entry<String, String> selection : option.selections().entrySet()) {
                selectedPackages.put(selection.getKey().toLowerCase(Locale.ROOT), selection.getValue());
            }
        }

        ArrayList<String> disabledPackages = new ArrayList<>();
        for (String disabledPackage : originalPreference.getDisabledPackages()) {
            if (option.selections().values().stream().noneMatch(packageKey -> packageKey.equalsIgnoreCase(disabledPackage))) {
                disabledPackages.add(disabledPackage);
            }
        }

        BundlePreference targetPreference = new BundlePreference(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                originalPreference.getSourceSha1(),
                false,
                disabledPackages,
                selectedBundleVariant,
                selectedPackages
        );

        VariantSelectionDecision selectionDecision = resolveVariantSelection(
                knownBundle.archiveDescriptor(),
                targetPreference,
                null,
                true
        );
        BundleStateSnapshot snapshot = captureBundleState(knownBundle.bundleId(), knownBundle.sourceZipName());
        BundleActionReport report = withBundleStateRollback(snapshot, hasBundleMutationWork(
                knownBundle.bundleId(),
                knownBundle.archiveDescriptor(),
                selectionDecision.targetPackages()
        ), () -> {
            uninstallConflictingVariantPackages(knownBundle.archiveDescriptor(), selectionDecision.targetPackages());
            BundleActionReport installReport = installEnabledPackages(
                    knownBundle.archiveDescriptor(),
                    selectionDecision.targetPackages(),
                    selectionDecision.preference(),
                    false
            );
            ensureRequiredPackagesInstalled(
                    knownBundle.bundleId(),
                    selectionDecision.targetPackages(),
                    installReport,
                    "Variant switch failed."
            );
            savePreference(selectionDecision.preference());
            return installReport;
        });
        clearPendingVariantPrompt();
        ArrayList<String> messages = new ArrayList<>(report.getMessages());
        messages.add("Selected variant: " + option.title() + " - " + option.displayName());
        return new BundleActionReport(
                report.getBundleId(),
                report.getSourceZipName(),
                report.getSucceededPackages(),
                report.getFailedPackages(),
                report.getDisabledPackages(),
                messages
        );
    }

    public BundleActionReport enableBundleById(String bundleIdQuery, String requestedPackageKey) throws BundleException {
        KnownBundle knownBundle = resolveKnownBundle(bundleIdQuery);
        if (knownBundle.archiveDescriptor() == null) {
            throw new BundleException("Bundle archive is not available for enable: " + bundleIdQuery);
        }

        BundlePreference preference = loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName());
        VariantSelectionDecision selectionDecision = resolveVariantSelection(
                knownBundle.archiveDescriptor(),
                preference,
                requestedPackageKey,
                true
        );
        preference = selectionDecision.preference();
        List<String> targetPackages = selectionDecision.targetPackages();

        if (requestedPackageKey == null || requestedPackageKey.isBlank()) {
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    preference.getSourceSha1(),
                    false,
                    List.of(),
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
        } else if (preference.isBundleDisabled()) {
            List<String> requestedPackageKeys = resolvePackageKeys(knownBundle.allKnownPackages(), requestedPackageKey);
            ArrayList<String> disabledPackages = new ArrayList<>();
            for (String installablePackage : knownBundle.installablePackages()) {
                if (!containsIgnoreCase(requestedPackageKeys, installablePackage)) {
                    disabledPackages.add(installablePackage);
                }
            }
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    preference.getSourceSha1(),
                    false,
                    disabledPackages,
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
        } else {
            List<String> requestedPackageKeys = resolvePackageKeys(knownBundle.allKnownPackages(), requestedPackageKey);
            ArrayList<String> disabledPackages = new ArrayList<>();
            for (String disabledPackage : preference.getDisabledPackages()) {
                if (!containsIgnoreCase(requestedPackageKeys, disabledPackage)) {
                    disabledPackages.add(disabledPackage);
                }
            }
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    preference.getSourceSha1(),
                    false,
                    disabledPackages,
                preference.getSelectedBundleVariant(),
                preference.getSelectedPackages()
            );
        }

        BundlePreference finalPreference = preference;
        BundleStateSnapshot snapshot = captureBundleState(knownBundle.bundleId(), knownBundle.sourceZipName());
        BundleActionReport report = withBundleStateRollback(snapshot, hasBundleMutationWork(
                knownBundle.bundleId(),
                knownBundle.archiveDescriptor(),
                targetPackages
        ), () -> {
            uninstallConflictingVariantPackages(knownBundle.archiveDescriptor(), targetPackages);
            BundleActionReport installReport = installEnabledPackages(knownBundle.archiveDescriptor(), targetPackages, finalPreference, true);
            ensureRequiredPackagesInstalled(
                    knownBundle.bundleId(),
                    targetPackages,
                    installReport,
                    "Enable request failed."
            );
            savePreference(finalPreference);
            return installReport;
        });
        clearPendingVariantPrompt();
        return withAdditionalMessages(report, selectionDecision.messages());
    }

    public BundleActionReport disableBundleById(String bundleIdQuery, String requestedPackageKey) throws BundleException {
        KnownBundle knownBundle = resolveKnownBundle(bundleIdQuery);
        BundlePreference preference = loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName());
        ArrayList<String> removedPackages = new ArrayList<>();

        if (requestedPackageKey == null || requestedPackageKey.isBlank()) {
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    preference.getSourceSha1(),
                    true,
                    List.of(),
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
            for (BundleRecord record : findBundleRecords(knownBundle.bundleId())) {
                removedPackages.add(record.getPackageKey());
            }
            if (!removedPackages.isEmpty()) {
                uninstallBundle(knownBundle.bundleId(), null);
            }
            savePreference(preference);
            clearBundleFailures(knownBundle.bundleId());
            return new BundleActionReport(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    List.of(),
                    List.of(),
                    deduplicatePackages(knownBundle.allKnownPackages()),
                    List.of()
            );
        }

        List<String> packageKeys = resolvePackageKeys(knownBundle.allKnownPackages(), requestedPackageKey);
        ArrayList<String> disabledPackages = new ArrayList<>(preference.getDisabledPackages());
        for (String packageKey : packageKeys) {
            if (!containsIgnoreCase(disabledPackages, packageKey)) {
                disabledPackages.add(packageKey);
            }
        }
        preference = new BundlePreference(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                preference.getSourceSha1(),
                false,
                disabledPackages,
                preference.getSelectedBundleVariant(),
                preference.getSelectedPackages()
        );

        List<String> installedPackageKeys = listInstalledPackageKeys(knownBundle.bundleId());
        for (String packageKey : packageKeys) {
            if (containsIgnoreCase(installedPackageKeys, packageKey)) {
                uninstallBundle(knownBundle.bundleId(), packageKey);
                removedPackages.add(packageKey);
            }
            clearPackageFailure(knownBundle.bundleId(), packageKey);
        }
        savePreference(preference);

        return new BundleActionReport(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                List.of(),
                List.of(),
                toCommandPackageNames(packageKeys),
                List.of()
        );
    }

    public List<BundleStatusView> listBundleStatusViews() {
        LinkedHashMap<String, KnownBundle> bundles = new LinkedHashMap<>();
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors(false)) {
            bundles.put(descriptor.bundleId(), new KnownBundle(
                    descriptor.bundleId(),
                    descriptor.sourceZipName(),
                    descriptor,
                    new ArrayList<>(descriptor.installablePackages()),
                    new ArrayList<>(descriptor.allPackages())
            ));
        }

        for (BundleSummary summary : listInstalledBundles()) {
            bundles.compute(summary.getBundleId(), (bundleId, existing) -> mergeKnownBundle(
                    existing,
                    bundleId,
                    summary.getSourceZipName(),
                    null,
                    summary.getPackageKeys()
            ));
        }

        for (BundlePreference preference : loadAllPreferences()) {
            bundles.compute(preference.getBundleId(), (bundleId, existing) -> mergeKnownBundle(
                    existing,
                    bundleId,
                    preference.getSourceZipName(),
                    null,
                    preference.getDisabledPackages()
            ));
        }

        ArrayList<BundleStatusView> views = new ArrayList<>(bundles.size());
        for (KnownBundle knownBundle : bundles.values()) {
            views.add(buildStatusView(knownBundle));
        }
        views.sort(Comparator
                .comparingInt((BundleStatusView view) -> parseBundleOrder(view.getBundleId()))
                .thenComparing(BundleStatusView::getBundleId, String.CASE_INSENSITIVE_ORDER));
        return views;
    }

    public List<BundleInstallResult> installBundle(String archiveFileName, String requestedPackageKey) throws BundleException {
        return installBundle(archiveFileName, requestedPackageKey, Set.of());
    }

    private List<BundleInstallResult> installBundle(
            String archiveFileName,
            String requestedPackageKey,
            Set<String> approvedOverwriteTargets
    ) throws BundleException {
        File archiveFile = resolveBundleArchiveFile(archiveFileName);
        String bundleId = stateStore.computeBundleId(archiveFile, plugin.getLogger());
        String bundleIdShort = shortId(bundleId);

        try (BundleArchive archive = openArchive(archiveFile)) {
            // Discover package roots at any depth so nested bundle variants work.
            ArchivePackageInfo packageInfo = archiveInspector.inspectArchivePackages(archive, installedServerPluginNames());
            List<BundlePackageDescriptor> packageDescriptors = packageInfo.packageDescriptors();
            if (packageDescriptors.isEmpty()) {
                throw new BundleException("Bundle must contain plugin package directories.");
            }

            BundleArchiveDescriptor descriptor = new BundleArchiveDescriptor(
                    bundleId,
                    archiveFile.getName(),
                    "",
                    packageInfo.installablePackages(),
                    packageInfo.allPackages(),
                    packageInfo.variantGroups(),
                    packageInfo.variantChoiceGroups(),
                    List.of(),
                    discoverMissingPluginKeys(packageDescriptors)
            );
            List<String> discoveryWarnings = buildBundleScanWarnings(descriptor, packageDescriptors);
            List<BundlePackageDescriptor> installablePackages = resolveRequestedPackages(packageDescriptors, requestedPackageKey);
            for (BundlePackageDescriptor packageDescriptor : installablePackages) {
                String recordId = recordId(bundleId, packageDescriptor.packageKey());
                if (stateStore.recordExists(recordId)) {
                    throw new BundleException("Package '" + packageDescriptor.packageKey()
                            + "' is already installed for bundle " + bundleIdShort + ".");
                }
            }

            ArrayList<BundleInstallResult> results = new ArrayList<>(installablePackages.size());
            try {
                for (BundlePackageDescriptor packageDescriptor : installablePackages) {
                    results.add(installPackage(
                            archive,
                            archiveFile.getName(),
                            bundleId,
                            bundleIdShort,
                            packageDescriptor,
                            approvedOverwriteTargets
                    ));
                }
            } catch (BundleException | RuntimeException ex) {
                throw rollbackBatchInstallFailure(results, ex);
            }

            if (results.isEmpty()) {
                if (requestedPackageKey != null && !requestedPackageKey.isBlank()) {
                    String packageKey = resolvePackageKey(packageInfo.allPackages(), requestedPackageKey);
                    if (!isPluginPackageInstallable(packageKey)) {
                        throw new BundleException(buildMissingPluginMessage(basePluginKey(packageKey)));
                    }
                }
                throw new BundleException("No supported packages were installed from " + archiveFile.getName() + ".");
            }

            if (!discoveryWarnings.isEmpty()) {
                BundleInstallResult first = results.get(0);
                ArrayList<String> combinedWarnings = new ArrayList<>(discoveryWarnings.size() + first.getWarnings().size());
                combinedWarnings.addAll(discoveryWarnings);
                combinedWarnings.addAll(first.getWarnings());
                results.set(0, new BundleInstallResult(first.getRecord(), combinedWarnings));
            }

            return results;
        } catch (IOException ex) {
            throw new BundleException("Failed to open bundle archive: " + archiveFile.getName(), ex);
        }
    }

    public List<BundleRecord> uninstallBundle(String bundleQuery, String requestedPackageKey) throws BundleException {
        List<BundleRecord> matchingRecords = findBundleRecords(bundleQuery);
        if (matchingRecords.isEmpty()) {
            throw new BundleException("Bundle not found: " + bundleQuery);
        }

        List<BundleRecord> selectedRecords = filterRecordsByPackage(matchingRecords, requestedPackageKey);
        if (selectedRecords.isEmpty()) {
            throw new BundleException("Package not found in bundle: " + requestedPackageKey);
        }

        selectedRecords.sort(Comparator.comparing(BundleRecord::getPackageKey).reversed());
        ArrayList<BundleRecord> removed = new ArrayList<>(selectedRecords.size());
        for (BundleRecord record : selectedRecords) {
            uninstallPackage(record);
            removed.add(record);
        }
        return removed;
    }

    public List<BundleSummary> listInstalledBundles() {
        return summarizeInstalledBundles(listInstalledPackageRecords());
    }

    public List<BundleRecord> listInstalledPackageRecords() {
        return stateStore.listInstalledPackageRecords(plugin.getLogger());
    }

    private BundleInstallResult installPackage(
            BundleArchive archive,
            String sourceZipName,
            String bundleId,
            String bundleIdShort,
            BundlePackageDescriptor packageDescriptor,
            Set<String> approvedOverwriteTargets
    ) throws BundleException {
        SupportedPluginInstaller installer = installerRegistry.find(packageDescriptor.pluginKey())
                .orElseThrow(() -> new BundleException("Unsupported package: " + packageDescriptor.packageKey()));
        String packageKey = packageDescriptor.packageKey();
        String installPrefix = packageInstallPrefix(bundleIdShort, packageKey);
        String recordId = recordId(bundleId, packageKey);
        ArrayList<BundleRecord.InstalledFile> installedFiles = new ArrayList<>();
        ArrayList<ResolvedBundleFile> plannedFiles = new ArrayList<>();
        ArrayList<BundleRecord.ConfigMutation> requestedMutations = new ArrayList<>();
        ArrayList<BundleRecord.ConfigMutation> appliedMutations = new ArrayList<>();
        LinkedHashSet<String> createdDirectories = new LinkedHashSet<>();
        LinkedHashSet<String> touchedTargets = new LinkedHashSet<>();
        ArrayList<String> warnings = new ArrayList<>();

        try {
            // Quet truoc de tao rename map noi bo cho package.
            planPluginEntries(
                    packageDescriptor.entries(),
                    packageDescriptor.rootPath(),
                    packageKey,
                    installer,
                    installPrefix,
                    touchedTargets,
                    plannedFiles
            );
            if (plannedFiles.isEmpty()) {
                throw new BundleException("Package '" + packageKey + "' does not contain any installable files.");
            }

            applyRenameOnConflictIfAllowed(
                    installer,
                    installPrefix,
                    packageKey,
                    approvedOverwriteTargets,
                    plannedFiles
            );
            queueOverwriteConflictsIfNeeded(
                    bundleId,
                    sourceZipName,
                    packageKey,
                    installer,
                    plannedFiles,
                    approvedOverwriteTargets
            );
            warnings.addAll(validateIdentityConflicts(archive, installer, packageKey, plannedFiles));

            ReferenceRewriteContext rewriteContext = new ReferenceRewriteContext(plannedFiles);
            installPluginEntries(
                    archive,
                    plannedFiles,
                    packageKey,
                    installer,
                    bundleId,
                    approvedOverwriteTargets,
                    createdDirectories,
                    installedFiles,
                    rewriteContext
            );

            mergeConfigMutations(requestedMutations, installer.buildConfigMutations(plannedFiles, installPrefix));
            validateConfigMutationPlan(packageKey, requestedMutations);
            appliedMutations.addAll(applyConfigMutations(requestedMutations, warnings));

            BundleRecord record = new BundleRecord(
                    recordId,
                    bundleId,
                    packageKey,
                    sourceZipName,
                    Instant.now().toEpochMilli(),
                    warnings,
                    installedFiles,
                    appliedMutations,
                    new ArrayList<>(createdDirectories)
            );
            stateStore.saveRecord(record);
            stateStore.deleteOverwriteConflict(bundleId, packageKey);
            return new BundleInstallResult(record, warnings);
        } catch (IOException ex) {
            throw rollbackFailedPackageInstall(
                    packageKey,
                    recordId,
                    appliedMutations,
                    installedFiles,
                    createdDirectories,
                    new BundleException("Failed to install package '" + packageKey + "'.", ex)
            );
        } catch (BundleException | RuntimeException ex) {
            throw rollbackFailedPackageInstall(
                    packageKey,
                    recordId,
                    appliedMutations,
                    installedFiles,
                    createdDirectories,
                    ex instanceof BundleException bundleException
                            ? bundleException
                            : new BundleException("Failed to install package '" + packageKey + "'.", ex)
            );
        }
    }

    private List<String> validateIdentityConflicts(
            BundleArchive archive,
            SupportedPluginInstaller installer,
            String packageKey,
            List<ResolvedBundleFile> plannedFiles
    ) throws BundleException {
        List<BundleInstallIdentity> incoming = installer.collectIncomingIdentities(plannedFiles, bundleFile -> {
            try (InputStream inputStream = archive.openInputStream(new BundleArchiveEntry(bundleFile.getSourceEntryName(), false))) {
                return inputStream.readAllBytes();
            } catch (IOException ex) {
                throw new BundleException("Failed to read bundle file " + bundleFile.getSourceEntryName() + ".", ex);
            }
        });

        ArrayList<String> warnings = new ArrayList<>();
        warnings.addAll(ensureNoInternalIdentityConflicts(packageKey, incoming));
        warnings.addAll(ensureNoExistingIdentityConflicts(
                packageKey,
                plannedFiles,
                incoming,
                installer.collectExistingIdentities(serverRoot)
        ));
        return deduplicatePackages(warnings);
    }

    private void queueOverwriteConflictsIfNeeded(
            String bundleId,
            String sourceZipName,
            String packageKey,
            SupportedPluginInstaller installer,
            List<ResolvedBundleFile> plannedFiles,
            Set<String> approvedOverwriteTargets
    ) throws BundleException {
        ArrayList<String> queuedTargets = new ArrayList<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            String targetRelativePath = PathUtils.normalizeRelativePath(plannedFile.getTargetRelativePath());
            Path targetPath = resolveServerPath(targetRelativePath);
            if (!Files.exists(targetPath)) {
                continue;
            }
            if (containsPathIgnoreCase(approvedOverwriteTargets, targetRelativePath)) {
                continue;
            }
            if (!installer.canRequestOverwrite(plannedFile)) {
                throw new BundleException(
                        "Refusing to overwrite existing file for package '" + packageKey + "': " + targetRelativePath
                );
            }
            queuedTargets.add(targetRelativePath);
        }

        if (queuedTargets.isEmpty()) {
            return;
        }

        BundleOverwriteConflict conflict = stateStore.saveOrUpdateOverwriteConflict(
                bundleId,
                sourceZipName,
                packageKey,
                deduplicatePackages(queuedTargets)
        );
        throw new BundleException(
                "Overwrite conflict queued as #" + conflict.getId()
                        + " for package '" + packageKey
                        + "'. Use /bm conflicts and /bm resolve " + conflict.getId() + " overwrite."
        );
    }

    private List<String> ensureNoInternalIdentityConflicts(
            String pluginKey,
            List<BundleInstallIdentity> incomingIdentities
    ) {
        LinkedHashMap<String, BundleInstallIdentity> seen = new LinkedHashMap<>();
        ArrayList<String> warnings = new ArrayList<>();
        for (BundleInstallIdentity identity : incomingIdentities) {
            BundleInstallIdentity previous = seen.putIfAbsent(identity.uniqueKey(), identity);
            if (previous == null) {
                continue;
            }

            warnings.add(
                    "Package '" + pluginKey + "' contains duplicate " + identity.getType()
                            + " '" + identity.getId() + "' in " + previous.getSource()
                            + " and " + identity.getSource() + "."
            );
        }
        return warnings;
    }

    private List<String> ensureNoExistingIdentityConflicts(
            String pluginKey,
            List<ResolvedBundleFile> plannedFiles,
            List<BundleInstallIdentity> incomingIdentities,
            List<BundleInstallIdentity> existingIdentities
    ) {
        LinkedHashSet<String> replacedTargets = new LinkedHashSet<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            replacedTargets.add(plannedFile.getTargetRelativePath().toLowerCase(Locale.ROOT));
        }

        LinkedHashMap<String, BundleInstallIdentity> existingByKey = new LinkedHashMap<>();
        for (BundleInstallIdentity identity : existingIdentities) {
            if (replacedTargets.contains(identity.getSource().toLowerCase(Locale.ROOT))) {
                continue;
            }
            existingByKey.putIfAbsent(identity.uniqueKey(), identity);
        }

        ArrayList<String> warnings = new ArrayList<>();
        for (BundleInstallIdentity identity : incomingIdentities) {
            BundleInstallIdentity existing = existingByKey.get(identity.uniqueKey());
            if (existing == null) {
                continue;
            }

            warnings.add(
                    "Package '" + pluginKey + "' conflicts with existing " + identity.getType()
                            + " '" + identity.getId() + "' at " + existing.getSource() + "."
            );
        }
        return warnings;
    }

    private void uninstallPackage(BundleRecord record) throws BundleException {
        rollbackConfigMutations(record.getConfigMutations());
        deleteInstalledFiles(record.getInstalledFiles());
        restoreBackups(record.getInstalledFiles());
        deleteCreatedDirectories(record.getCreatedDirectories());
        cleanupUninstalledRecord(record.getId());
    }

    private void planPluginEntries(
            List<BundleArchiveEntry> entries,
            String packageRootPath,
            String packageKey,
            SupportedPluginInstaller installer,
            String installPrefix,
            Set<String> touchedTargets,
            List<ResolvedBundleFile> plannedFiles
    ) throws BundleException {
        List<BundleArchiveEntry> sortedEntries = new ArrayList<>(entries);
        sortedEntries.sort(Comparator.comparing(BundleArchiveEntry::getName));

        for (BundleArchiveEntry entry : sortedEntries) {
            String normalizedEntryName = PathUtils.normalizeZipPath(entry.getName());
            String relativePath = trimPackageRoot(normalizedEntryName, packageRootPath);
            Optional<ResolvedBundleFile> optionalResolved = installer.resolveFile(relativePath, installPrefix);
            if (optionalResolved.isEmpty()) {
                continue;
            }

            ResolvedBundleFile resolvedFile = optionalResolved.get();
            String targetRelativePath = PathUtils.normalizeRelativePath(resolvedFile.getTargetRelativePath());
            String targetKey = targetRelativePath.toLowerCase(Locale.ROOT);
            if (!touchedTargets.add(targetKey)) {
                throw new BundleException("Package '" + packageKey + "' contains duplicate target path: " + targetRelativePath);
            }

            plannedFiles.add(new ResolvedBundleFile(
                    normalizedEntryName,
                    resolvedFile.getSourceRelativePath(),
                    targetRelativePath
            ));
        }
    }

    private void installPluginEntries(
            BundleArchive archive,
            List<ResolvedBundleFile> plannedFiles,
            String packageKey,
            SupportedPluginInstaller installer,
            String bundleId,
            Set<String> approvedOverwriteTargets,
            LinkedHashSet<String> createdDirectories,
            List<BundleRecord.InstalledFile> installedFiles,
            ReferenceRewriteContext rewriteContext
    ) throws IOException, BundleException {
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            String targetRelativePath = PathUtils.normalizeRelativePath(plannedFile.getTargetRelativePath());
            Path targetPath = resolveServerPath(targetRelativePath);

            ensureParentDirectories(targetPath.getParent(), createdDirectories);
            boolean overwriteApproved = containsPathIgnoreCase(approvedOverwriteTargets, targetRelativePath);
            String backupPath = "";
            if (Files.exists(targetPath)) {
                if (!overwriteApproved) {
                    throw new BundleException("Overwrite approval is missing for " + targetRelativePath + ".");
                }
                backupPath = PathUtils.normalizeRelativePath(recordId(bundleId, packageKey) + "/" + targetRelativePath);
                Path backupTarget = backupDirectory.toPath().resolve(backupPath).normalize();
                createDirectory(backupTarget.getParent());
                Files.copy(targetPath, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }

            BundleArchiveEntry archiveEntry = new BundleArchiveEntry(plannedFile.getSourceEntryName(), false);

            if (installer.shouldRewriteFileContent(plannedFile)) {
                byte[] rewrittenBytes;
                try (InputStream inputStream = archive.openInputStream(archiveEntry)) {
                    rewrittenBytes = installer.rewriteFileContent(plannedFile, inputStream.readAllBytes(), rewriteContext);
                }
                if (overwriteApproved) {
                    Files.write(targetPath, rewrittenBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Files.write(targetPath, rewrittenBytes, StandardOpenOption.CREATE_NEW);
                }
            } else {
                try (InputStream inputStream = archive.openInputStream(archiveEntry)) {
                    if (overwriteApproved) {
                        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(inputStream, targetPath);
                    }
                }
            }

            installedFiles.add(new BundleRecord.InstalledFile(
                    plannedFile.getSourceEntryName(),
                    packageKey,
                    targetRelativePath,
                    backupPath
            ));
        }
    }

    private void applyRenameOnConflictIfAllowed(
            SupportedPluginInstaller installer,
            String installPrefix,
            String packageKey,
            Set<String> approvedOverwriteTargets,
            List<ResolvedBundleFile> plannedFiles
    ) throws BundleException {
        ArrayList<ResolvedBundleFile> adjustedFiles = new ArrayList<>(plannedFiles.size());
        LinkedHashSet<String> touchedTargets = new LinkedHashSet<>();
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            String originalTargetPath = PathUtils.normalizeRelativePath(plannedFile.getTargetRelativePath());
            ResolvedBundleFile resolvedFile = plannedFile.withTargetRelativePath(originalTargetPath);

            if (!containsPathIgnoreCase(approvedOverwriteTargets, originalTargetPath)
                    && Files.exists(resolveServerPath(originalTargetPath))) {
                Optional<ResolvedBundleFile> renamedFile = installer.resolveRenameOnConflict(resolvedFile, installPrefix);
                if (renamedFile.isPresent()) {
                    String renamedTargetPath = PathUtils.normalizeRelativePath(renamedFile.get().getTargetRelativePath());
                    resolvedFile = new ResolvedBundleFile(
                            plannedFile.getSourceEntryName(),
                            renamedFile.get().getSourceRelativePath(),
                            renamedTargetPath
                    );
                }
            }

            String targetKey = resolvedFile.getTargetRelativePath().toLowerCase(Locale.ROOT);
            if (!touchedTargets.add(targetKey)) {
                throw new BundleException(
                        "Package '" + packageKey + "' contains duplicate target path: "
                                + resolvedFile.getTargetRelativePath()
                );
            }
            adjustedFiles.add(resolvedFile);
        }

        plannedFiles.clear();
        plannedFiles.addAll(adjustedFiles);
    }

    private List<BundlePackageDescriptor> resolveRequestedPackages(
            List<BundlePackageDescriptor> packageDescriptors,
            String requestedPackageKey
    ) throws BundleException {
        if (requestedPackageKey != null && !requestedPackageKey.isBlank()) {
            List<String> matchingPackageKeys = resolvePackageKeys(
                    packageDescriptors.stream()
                            .map(BundlePackageDescriptor::packageKey)
                            .toList(),
                    requestedPackageKey
            );
            ArrayList<BundlePackageDescriptor> selectedDescriptors = new ArrayList<>();
            for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
                if (packageDescriptor.supported() && containsIgnoreCase(matchingPackageKeys, packageDescriptor.packageKey())) {
                    if (!isPluginPackageInstallable(packageDescriptor.packageKey())) {
                        throw new BundleException(buildMissingPluginMessage(packageDescriptor.pluginKey()));
                    }
                    selectedDescriptors.add(packageDescriptor);
                }
            }
            if (!selectedDescriptors.isEmpty()) {
                return selectedDescriptors;
            }
            throw new BundleException("Bundle does not contain package '" + requestedPackageKey + "'.");
        }

        ArrayList<BundlePackageDescriptor> installable = new ArrayList<>();
        for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
            if (packageDescriptor.supported() && isPluginPackageInstallable(packageDescriptor.packageKey())) {
                installable.add(packageDescriptor);
            }
        }

        if (installable.isEmpty()) {
            throw new BundleException("No supported packages found in bundle.");
        }

        installable.sort(Comparator.comparing(BundlePackageDescriptor::packageKey, String.CASE_INSENSITIVE_ORDER));
        return installable;
    }

    private List<BundleRecord> filterRecordsByPackage(List<BundleRecord> records, String requestedPackageKey) {
        if (requestedPackageKey == null || requestedPackageKey.isBlank()) {
            return new ArrayList<>(records);
        }

        List<String> matchingPackageKeys;
        try {
            matchingPackageKeys = resolvePackageKeys(
                    records.stream()
                            .map(BundleRecord::getPackageKey)
                            .toList(),
                    requestedPackageKey
            );
        } catch (BundleException ex) {
            return List.of();
        }
        ArrayList<BundleRecord> filtered = new ArrayList<>();
        for (BundleRecord record : records) {
            if (containsIgnoreCase(matchingPackageKeys, record.getPackageKey())) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private List<BundleRecord> findBundleRecords(String bundleQuery) {
        if (bundleQuery == null || bundleQuery.isBlank()) {
            return List.of();
        }

        String normalizedQuery = bundleQuery.trim().toLowerCase(Locale.ROOT);
        ArrayList<BundleRecord> matching = new ArrayList<>();
        for (BundleRecord record : listInstalledPackageRecords()) {
            if (matchesBundleQuery(record, normalizedQuery)) {
                matching.add(record);
            }
        }
        return matching;
    }

    private List<BundleSummary> summarizeInstalledBundles(List<BundleRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, ArrayList<BundleRecord>> grouped = new LinkedHashMap<>();
        for (BundleRecord record : records) {
            grouped.computeIfAbsent(record.getBundleId(), ignored -> new ArrayList<>()).add(record);
        }

        ArrayList<BundleSummary> summaries = new ArrayList<>(grouped.size());
        for (List<BundleRecord> bundleRecords : grouped.values()) {
            bundleRecords.sort(Comparator.comparing(BundleRecord::getPackageKey, String.CASE_INSENSITIVE_ORDER));

            BundleRecord first = bundleRecords.get(0);
            ArrayList<String> packageKeys = new ArrayList<>(bundleRecords.size());
            int installedFileCount = 0;
            long installedAt = 0L;
            for (BundleRecord record : bundleRecords) {
                packageKeys.add(record.getPackageKey());
                installedFileCount += record.getInstalledFiles().size();
                installedAt = Math.max(installedAt, record.getInstalledAtEpochMillis());
            }

            summaries.add(new BundleSummary(
                    first.getBundleId(),
                    first.getSourceZipName(),
                    installedAt,
                    packageKeys,
                    installedFileCount
            ));
        }

        summaries.sort(Comparator.comparingLong(BundleSummary::getInstalledAtEpochMillis).reversed());
        return summaries;
    }

    private BundleActionReport reconcileBundle(BundleArchiveDescriptor descriptor) throws BundleException {
        BundlePreference storedPreference = loadPreference(descriptor.bundleId(), descriptor.sourceZipName());
        boolean hashChanged = storedPreference.getSourceSha1() != null
                && !storedPreference.getSourceSha1().equalsIgnoreCase(descriptor.sourceSha1());
        BundlePreference preference = updateStoredBundleMetadata(storedPreference, descriptor);
        if (preference.isBundleDisabled()) {
            if (hashChanged || !Objects.equals(storedPreference.getSourceSha1(), descriptor.sourceSha1())
                    || !storedPreference.getSourceZipName().equalsIgnoreCase(descriptor.sourceZipName())) {
                savePreference(preference);
            }
            clearBundleFailures(descriptor.bundleId());
            return new BundleActionReport(
                    descriptor.bundleId(),
                    descriptor.sourceZipName(),
                    List.of(),
                    List.of(),
                    descriptor.installablePackages(),
                    hashChanged
                            ? List.of(formatBundleScopedMessage(
                            descriptor,
                            "Bundle content changed. Stored SHA-1 was updated for disabled bundle."
                    ))
                            : List.of()
            );
        }

        VariantSelectionDecision selectionDecision = resolveVariantSelection(descriptor, preference, null, true);
        preference = selectionDecision.preference();

        if (!hashChanged && !hasSyncWork(descriptor, selectionDecision.targetPackages())) {
            if (!Objects.equals(storedPreference.getSourceSha1(), descriptor.sourceSha1())
                    || !storedPreference.getSourceZipName().equalsIgnoreCase(descriptor.sourceZipName())
                    || selectionDecision.preference() != storedPreference) {
                savePreference(selectionDecision.preference());
            }
            clearBundleFailures(descriptor.bundleId());
            return new BundleActionReport(
                    descriptor.bundleId(),
                    descriptor.sourceZipName(),
                    List.of(),
                    List.of(),
                    List.of(),
                    contextualizeBundleMessages(descriptor, selectionDecision.messages())
            );
        }

        List<String> installedPackagesBeforeSync = listInstalledPackageKeys(descriptor.bundleId());
        List<String> criticalPackages = determineCriticalPackages(
                descriptor,
                selectionDecision.targetPackages(),
                installedPackagesBeforeSync
        );
        BundleStateSnapshot snapshot = captureBundleState(descriptor.bundleId(), storedPreference.getSourceZipName());
        BundlePreference finalPreference = preference;
        BundleActionReport report = withBundleStateRollback(snapshot, hashChanged
                || hasBundleMutationWork(descriptor.bundleId(), descriptor, selectionDecision.targetPackages()), () -> {
            if (hashChanged && !installedPackagesBeforeSync.isEmpty()) {
                uninstallBundle(descriptor.bundleId(), null);
            }
            uninstallConflictingVariantPackages(descriptor, selectionDecision.targetPackages());
            BundleActionReport installReport = installEnabledPackages(
                    descriptor,
                    selectionDecision.targetPackages(),
                    finalPreference,
                    false
            );
            ensureRequiredPackagesInstalled(
                    descriptor.bundleId(),
                    criticalPackages,
                    installReport,
                    "Bundle sync failed."
            );
            savePreference(finalPreference);
            clearBundleFailures(descriptor.bundleId());
            return installReport;
        });
        ArrayList<String> messages = new ArrayList<>(contextualizeBundleMessages(descriptor, selectionDecision.messages()));
        if (hashChanged) {
            messages.add(formatBundleScopedMessage(descriptor, "Bundle content changed. Reinstalled from updated source."));
        }
        messages.addAll(report.getMessages());
        return new BundleActionReport(
                report.getBundleId(),
                report.getSourceZipName(),
                report.getSucceededPackages(),
                report.getFailedPackages(),
                report.getDisabledPackages(),
                deduplicatePackages(messages)
        );
    }

    private boolean hasSyncWork(BundleArchiveDescriptor descriptor, List<String> targetPackages) {
        List<String> installedPackages = listInstalledPackageKeys(descriptor.bundleId());
        for (String targetPackage : targetPackages) {
            if (!containsIgnoreCase(installedPackages, targetPackage)) {
                return true;
            }
        }

        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            String selectedPackage = targetPackages.stream()
                    .filter(target -> variantGroup.options().stream().anyMatch(option -> option.packageKey().equalsIgnoreCase(target)))
                    .findFirst()
                    .orElse(null);
            for (BundleVariantOption option : variantGroup.options()) {
                if (selectedPackage != null && option.packageKey().equalsIgnoreCase(selectedPackage)) {
                    continue;
                }
                if (containsIgnoreCase(installedPackages, option.packageKey())) {
                    return true;
                }
            }
        }

        return false;
    }

    private BundleActionReport installEnabledPackages(
            BundleArchiveDescriptor descriptor,
            List<String> targetPackages,
            BundlePreference preference,
            boolean clearBundleDisabledState
    ) {
        ArrayList<String> succeededPackages = new ArrayList<>();
        ArrayList<String> failedPackages = new ArrayList<>();
        ArrayList<String> disabledPackages = new ArrayList<>();
        ArrayList<String> messages = new ArrayList<>();

        if (clearBundleDisabledState) {
            clearBundleFailures(descriptor.bundleId());
        }

        for (String installablePackage : descriptor.installablePackages()) {
            if (!targetPackages.stream().anyMatch(target -> target.equalsIgnoreCase(installablePackage))) {
                if (preference.isBundleDisabled() || containsIgnoreCase(preference.getDisabledPackages(), installablePackage)) {
                    disabledPackages.add(installablePackage);
                }
                continue;
            }

            if (containsIgnoreCase(listInstalledPackageKeys(descriptor.bundleId()), installablePackage)) {
                clearPackageFailure(descriptor.bundleId(), installablePackage);
                continue;
            }

            if (!isPluginPackageInstallable(installablePackage)) {
                clearPackageFailure(descriptor.bundleId(), installablePackage);
                messages.add(formatPackageScopedMessage(
                        descriptor,
                        installablePackage,
                        buildMissingPluginMessage(basePluginKey(installablePackage))
                ));
                continue;
            }

            try {
                List<BundleInstallResult> results = installBundle(descriptor.sourceZipName(), installablePackage);
                for (BundleInstallResult result : results) {
                    for (String warning : result.getWarnings()) {
                        messages.add(formatPackageScopedMessage(descriptor, installablePackage, warning));
                    }
                }
                clearPackageFailure(descriptor.bundleId(), installablePackage);
                succeededPackages.add(installablePackage);
            } catch (BundleException ex) {
                recordBundleFailure(descriptor.bundleId(), installablePackage, ex.getMessage());
                failedPackages.add(installablePackage);
                messages.add(formatPackageScopedMessage(descriptor, installablePackage, ex.getMessage()));
            }
        }

        return new BundleActionReport(
                descriptor.bundleId(),
                descriptor.sourceZipName(),
                deduplicatePackages(succeededPackages),
                deduplicatePackages(failedPackages),
                deduplicatePackages(disabledPackages),
                deduplicatePackages(messages)
        );
    }

    private VariantSelectionDecision resolveVariantSelection(
            BundleArchiveDescriptor descriptor,
            BundlePreference preference,
            String requestedPackageKey,
            boolean autoSelect
    ) throws BundleException {
        if (requestedPackageKey != null && !requestedPackageKey.isBlank()) {
            ArrayList<String> messages = new ArrayList<>();
            List<String> matchingPackageKeys = resolvePackageKeys(descriptor.installablePackages(), requestedPackageKey);
            String packageKey = selectRequestedPackageKey(
                    descriptor,
                    preference,
                    matchingPackageKeys,
                    autoSelect,
                    messages
            );
            BundlePreference updatedPreference = rememberSelectedVariant(preference, descriptor, packageKey);
            return new VariantSelectionDecision(updatedPreference, List.of(packageKey), messages);
        }

        LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>(preference.getSelectedPackages());
        ArrayList<String> targetPackages = new ArrayList<>();
        ArrayList<String> messages = new ArrayList<>();

        VariantChoiceGroup bundleChoiceGroup = findBundleChoiceGroup(descriptor);
        String selectedBundleVariant = preference.getSelectedBundleVariant();
        VariantChoiceOption selectedBundleOption = null;
        if (bundleChoiceGroup != null) {
            selectedBundleOption = findMatchingBundleChoiceOption(bundleChoiceGroup, selectedBundleVariant);
            if (selectedBundleOption == null && autoSelect && !bundleChoiceGroup.options().isEmpty()) {
                selectedBundleOption = bundleChoiceGroup.options().get(0);
                selectedBundleVariant = selectedBundleOption.displayName();
                messages.add("Auto-selected variant: Bundle - " + selectedBundleOption.displayName());
            }
        }

        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            List<String> enabledOptions = new ArrayList<>();
            for (BundleVariantOption option : variantGroup.options()) {
                if (!containsIgnoreCase(preference.getDisabledPackages(), option.packageKey())) {
                    enabledOptions.add(option.packageKey());
                }
            }
            if (enabledOptions.isEmpty()) {
                continue;
            }

            String selectedPackage = selectedPackages.get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
            if (selectedPackage == null && selectedBundleOption != null) {
                selectedPackage = selectedBundleOption.selections().get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
            }
            boolean selectionAvailable = false;
            if (selectedPackage != null) {
                for (String enabledOption : enabledOptions) {
                    if (enabledOption.equalsIgnoreCase(selectedPackage)) {
                        selectionAvailable = true;
                        break;
                    }
                }
            }
            if (!selectionAvailable) {
                if (!autoSelect) {
                    continue;
                }
                selectedPackage = enabledOptions.get(0);
                selectedPackages.put(variantGroup.pluginKey().toLowerCase(Locale.ROOT), selectedPackage);
                BundleVariantOption selectedOption = findVariantOption(variantGroup, selectedPackage);
                if (selectedOption != null) {
                    messages.add("Auto-selected variant: " + variantGroup.pluginKey() + " - " + selectedOption.displayName());
                }
            }
            targetPackages.add(selectedPackage);
        }

        for (String packageKey : descriptor.installablePackages()) {
            if (containsIgnoreCase(preference.getDisabledPackages(), packageKey)) {
                continue;
            }
            if (descriptor.variantGroups().stream().anyMatch(group ->
                    group.options().stream().anyMatch(option -> option.packageKey().equalsIgnoreCase(packageKey)))) {
                continue;
            }
            targetPackages.add(packageKey);
        }

        BundlePreference updatedPreference = new BundlePreference(
                preference.getBundleId(),
                preference.getSourceZipName(),
                preference.getSourceSha1(),
                preference.isBundleDisabled(),
                preference.getDisabledPackages(),
                selectedBundleVariant,
                selectedPackages
        );
        return new VariantSelectionDecision(updatedPreference, deduplicatePackages(targetPackages), messages);
    }

    private BundlePreference rememberSelectedVariant(
            BundlePreference preference,
            BundleArchiveDescriptor descriptor,
            String packageKey
    ) {
        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            if (variantGroup.options().stream().noneMatch(option -> option.packageKey().equalsIgnoreCase(packageKey))) {
                continue;
            }

            LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>(preference.getSelectedPackages());
            selectedPackages.put(variantGroup.pluginKey().toLowerCase(Locale.ROOT), packageKey);
            return new BundlePreference(
                    preference.getBundleId(),
                    preference.getSourceZipName(),
                    preference.getSourceSha1(),
                    preference.isBundleDisabled(),
                    preference.getDisabledPackages(),
                    preference.getSelectedBundleVariant(),
                    selectedPackages
            );
        }
        return preference;
    }

    private VariantChoiceGroup findBundleChoiceGroup(BundleArchiveDescriptor descriptor) {
        for (VariantChoiceGroup choiceGroup : descriptor.variantChoiceGroups()) {
            if ("Bundle".equalsIgnoreCase(choiceGroup.title())) {
                return choiceGroup;
            }
        }
        return null;
    }

    private VariantChoiceOption findMatchingBundleChoiceOption(
            VariantChoiceGroup bundleChoiceGroup,
            String selectedBundleVariant
    ) {
        if (selectedBundleVariant == null || selectedBundleVariant.isBlank()) {
            return null;
        }
        for (VariantChoiceOption option : bundleChoiceGroup.options()) {
            if (option.displayName().equalsIgnoreCase(selectedBundleVariant)) {
                return option;
            }
        }
        return null;
    }

    private void uninstallConflictingVariantPackages(BundleArchiveDescriptor descriptor, List<String> targetPackages) throws BundleException {
        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            String selectedPackage = targetPackages.stream()
                    .filter(target -> variantGroup.options().stream().anyMatch(option -> option.packageKey().equalsIgnoreCase(target)))
                    .findFirst()
                    .orElse(null);
            uninstallOtherVariantPackages(descriptor.bundleId(), variantGroup.pluginKey(), selectedPackage);
        }
    }

    private void uninstallOtherVariantPackages(String bundleId, String pluginKey, String selectedPackage) throws BundleException {
        for (BundleRecord record : findBundleRecords(bundleId)) {
            if (!basePluginKey(record.getPackageKey()).equalsIgnoreCase(pluginKey)) {
                continue;
            }
            if (selectedPackage != null && record.getPackageKey().equalsIgnoreCase(selectedPackage)) {
                continue;
            }
            uninstallBundle(bundleId, record.getPackageKey());
        }
    }

    private BundleVariantOption findVariantOption(BundleVariantGroup variantGroup, String packageKey) {
        for (BundleVariantOption option : variantGroup.options()) {
            if (option.packageKey().equalsIgnoreCase(packageKey)) {
                return option;
            }
        }
        return null;
    }

    private List<String> selectTargetPackages(List<String> installablePackages, String requestedPackageKey) throws BundleException {
        if (requestedPackageKey == null || requestedPackageKey.isBlank()) {
            return new ArrayList<>(installablePackages);
        }

        return resolvePackageKeys(installablePackages, requestedPackageKey);
    }

    private BundleActionReport withAdditionalMessages(BundleActionReport report, List<String> messages) {
        if (messages.isEmpty()) {
            return report;
        }

        ArrayList<String> combined = new ArrayList<>(report.getMessages());
        combined.addAll(messages);
        return new BundleActionReport(
                report.getBundleId(),
                report.getSourceZipName(),
                report.getSucceededPackages(),
                report.getFailedPackages(),
                report.getDisabledPackages(),
                deduplicatePackages(combined)
        );
    }

    private void appendVariantPrompt(
            BundleArchiveDescriptor descriptor,
            List<VariantPromptOption> promptOptions,
            List<String> promptMessages
    ) {
        if (descriptor.variantChoiceGroups().isEmpty()) {
            return;
        }

        promptMessages.add("&aMultiple variant detected. Use \"/bm chose <index>\" to swich.");
        for (VariantChoiceGroup group : descriptor.variantChoiceGroups()) {
            promptMessages.add("--- " + group.title() + " ---");
            for (VariantChoiceOption option : group.options()) {
                int index = promptOptions.size() + 1;
                promptOptions.add(new VariantPromptOption(
                        index,
                        descriptor.bundleId(),
                        descriptor.sourceZipName(),
                        group.title(),
                        option.displayName(),
                        option.selections()
                ));
                promptMessages.add("    " + index + ". " + option.displayName());
            }
        }
    }

    private boolean isNonSelectedVariant(BundleArchiveDescriptor descriptor, BundlePreference preference, String packageKey) {
        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            boolean belongsToGroup = variantGroup.options().stream()
                    .anyMatch(option -> option.packageKey().equalsIgnoreCase(packageKey));
            if (!belongsToGroup) {
                continue;
            }

            String selectedPackage = preference.getSelectedPackages().get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
            return selectedPackage != null && !selectedPackage.equalsIgnoreCase(packageKey);
        }
        return false;
    }

    private String displayPackageName(BundleArchiveDescriptor descriptor, String packageKey) {
        if (descriptor != null) {
            for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
                for (BundleVariantOption option : variantGroup.options()) {
                    if (option.packageKey().equalsIgnoreCase(packageKey)) {
                        return variantGroup.pluginKey() + " - " + option.displayName();
                    }
                }
            }
        }
        return basePluginKey(packageKey);
    }

    private String formatBundleScopedMessage(BundleArchiveDescriptor descriptor, String message) {
        if (descriptor == null) {
            return message;
        }
        return "[" + descriptor.sourceZipName() + "] " + message;
    }

    private List<String> contextualizeBundleMessages(BundleArchiveDescriptor descriptor, List<String> messages) {
        ArrayList<String> contextualized = new ArrayList<>(messages.size());
        for (String message : messages) {
            contextualized.add(formatBundleScopedMessage(descriptor, message));
        }
        return contextualized;
    }

    private String formatPackageScopedMessage(
            BundleArchiveDescriptor descriptor,
            String packageKey,
            String message
    ) {
        StringBuilder builder = new StringBuilder();
        if (descriptor != null) {
            builder.append('[').append(descriptor.sourceZipName());
            if (shouldShowPackageContext(descriptor, packageKey)) {
                builder.append(" | ").append(displayPackageName(descriptor, packageKey));
            }
            builder.append("] ");
        }
        builder.append(message);
        return builder.toString();
    }

    private boolean shouldShowPackageContext(BundleArchiveDescriptor descriptor, String packageKey) {
        if (descriptor == null || packageKey == null || packageKey.isBlank()) {
            return false;
        }
        return descriptor.installablePackages().size() > 1 || packageKey.contains("@");
    }

    private String basePluginKey(String packageKey) {
        int separator = packageKey.indexOf('@');
        return separator < 0 ? packageKey : packageKey.substring(0, separator);
    }

    private List<String> buildBundleScanWarnings(
            BundleArchiveDescriptor descriptor,
            List<BundlePackageDescriptor> packageDescriptors
    ) {
        ArrayList<String> warnings = new ArrayList<>();
        for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
            String pluginKey = packageDescriptor.pluginKey();
            if (packageDescriptor.supported()) {
                continue;
            }

            if (isInstalledServerPlugin(pluginKey)) {
                warnings.add(formatPackageScopedMessage(
                        descriptor,
                        packageDescriptor.packageKey(),
                        "Plugin '" + pluginKey + "' is installed on the server, but BundleManager does not support this package yet. "
                                + "Please open an issue: " + ISSUE_URL
                ));
            }
        }
        return deduplicatePackages(warnings);
    }

    private String buildMissingPluginMessage(String pluginKey) {
        return "Plugin '" + pluginKey + "' is not installed on the server.";
    }

    private List<String> filterNonMissingPluginMessages(List<String> messages) {
        ArrayList<String> filtered = new ArrayList<>(messages.size());
        for (String message : messages) {
            if (!isMissingPluginMessage(message)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private boolean isMissingPluginMessage(String message) {
        return message != null && message.contains("is not installed on the server.");
    }

    private List<String> discoverMissingPluginKeys(List<BundlePackageDescriptor> packageDescriptors) {
        TreeSet<String> missingPlugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
            if (!packageDescriptor.supported()) {
                continue;
            }
            if (isPluginPackageInstallable(packageDescriptor.packageKey())) {
                continue;
            }
            missingPlugins.add(packageDescriptor.pluginKey());
        }
        return new ArrayList<>(missingPlugins);
    }

    private List<MissingPluginRequirement> buildMissingPluginRequirements(BundleArchiveDescriptor descriptor) {
        ArrayList<MissingPluginRequirement> requirements = new ArrayList<>();
        for (String pluginName : descriptor.missingPlugins()) {
            requirements.add(new MissingPluginRequirement(pluginName, List.of(descriptor.bundleId())));
        }
        return requirements;
    }

    private List<MissingPluginRequirement> mergeMissingPluginRequirements(List<MissingPluginRequirement> requirements) {
        LinkedHashMap<String, String> originalNames = new LinkedHashMap<>();
        LinkedHashMap<String, TreeSet<String>> bundleIdsByPlugin = new LinkedHashMap<>();
        for (MissingPluginRequirement requirement : requirements) {
            String normalizedPluginName = requirement.pluginName().toLowerCase(Locale.ROOT);
            originalNames.putIfAbsent(normalizedPluginName, requirement.pluginName());
            TreeSet<String> bundleIds = bundleIdsByPlugin.computeIfAbsent(
                    normalizedPluginName,
                    ignored -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)
            );
            bundleIds.addAll(requirement.bundleIds());
        }

        ArrayList<MissingPluginRequirement> merged = new ArrayList<>(bundleIdsByPlugin.size());
        for (Map.Entry<String, TreeSet<String>> entry : bundleIdsByPlugin.entrySet()) {
            merged.add(new MissingPluginRequirement(
                    originalNames.getOrDefault(entry.getKey(), entry.getKey()),
                    new ArrayList<>(entry.getValue())
            ));
        }
        merged.sort(Comparator.comparing(MissingPluginRequirement::pluginName, String.CASE_INSENSITIVE_ORDER));
        return merged;
    }

    private boolean isPluginPackageInstallable(String packageKey) {
        String pluginKey = basePluginKey(packageKey);
        return "ResourcePack".equalsIgnoreCase(pluginKey) || isInstalledServerPlugin(pluginKey);
    }

    private boolean isInstalledServerPlugin(String pluginKey) {
        return installedServerPluginNames().containsKey(pluginKey.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> installedServerPluginNames() {
        LinkedHashMap<String, String> installed = new LinkedHashMap<>();
        if (plugin.getServer() == null || plugin.getServer().getPluginManager() == null) {
            return installed;
        }
        for (Plugin installedPlugin : plugin.getServer().getPluginManager().getPlugins()) {
            if (installedPlugin == null || installedPlugin.getDescription() == null) {
                continue;
            }
            String pluginName = installedPlugin.getDescription().getName();
            if (pluginName == null || pluginName.isBlank()) {
                continue;
            }
            installed.put(pluginName.toLowerCase(Locale.ROOT), pluginName);
        }
        return installed;
    }

    private List<String> resolvePackageKeys(List<String> packageKeys, String requestedPackageKey) throws BundleException {
        ArrayList<String> exactMatches = new ArrayList<>();
        for (String packageKey : packageKeys) {
            if (packageKey.equalsIgnoreCase(requestedPackageKey)) {
                exactMatches.add(packageKey);
            }
        }
        if (!exactMatches.isEmpty()) {
            return deduplicatePackages(exactMatches);
        }

        ArrayList<String> baseMatches = new ArrayList<>();
        for (String packageKey : packageKeys) {
            if (basePluginKey(packageKey).equalsIgnoreCase(requestedPackageKey)) {
                baseMatches.add(packageKey);
            }
        }
        if (!baseMatches.isEmpty()) {
            return deduplicatePackages(baseMatches);
        }

        throw new BundleException("Package not found in bundle: " + requestedPackageKey);
    }

    private String resolvePackageKey(List<String> packageKeys, String requestedPackageKey) throws BundleException {
        return resolvePackageKeys(packageKeys, requestedPackageKey).get(0);
    }

    private KnownBundle resolveKnownBundle(String bundleIdQuery) throws BundleException {
        if (bundleIdQuery == null || bundleIdQuery.isBlank()) {
            throw new BundleException("Bundle id cannot be empty.");
        }

        String normalizedQuery = bundleIdQuery.trim().toLowerCase(Locale.ROOT);
        for (BundleStatusView statusView : listBundleStatusViews()) {
            if (statusView.getBundleId().toLowerCase(Locale.ROOT).startsWith(normalizedQuery)
                    || statusView.getBundleShortId().toLowerCase(Locale.ROOT).startsWith(normalizedQuery)) {
                return findKnownBundleById(statusView.getBundleId());
            }
        }

        throw new BundleException("Bundle not found: " + bundleIdQuery);
    }

    private KnownBundle findKnownBundleById(String bundleId) {
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors(false)) {
            if (descriptor.bundleId().equals(bundleId)) {
                return new KnownBundle(
                        descriptor.bundleId(),
                        descriptor.sourceZipName(),
                        descriptor,
                        new ArrayList<>(descriptor.installablePackages()),
                        new ArrayList<>(descriptor.allPackages())
                );
            }
        }

        List<BundleRecord> records = findBundleRecords(bundleId);
        if (!records.isEmpty()) {
            ArrayList<String> packageKeys = new ArrayList<>();
            for (BundleRecord record : records) {
                packageKeys.add(record.getPackageKey());
            }
            BundleRecord first = records.get(0);
            return new KnownBundle(first.getBundleId(), first.getSourceZipName(), null, packageKeys, packageKeys);
        }

        BundlePreference preference = loadPreference(bundleId, bundleId);
        return new KnownBundle(preference.getBundleId(), preference.getSourceZipName(), null, preference.getDisabledPackages(), preference.getDisabledPackages());
    }

    private KnownBundle mergeKnownBundle(
            KnownBundle existing,
            String bundleId,
            String sourceZipName,
            BundleArchiveDescriptor descriptor,
            List<String> packageKeys
    ) {
        ArrayList<String> mergedInstallable = new ArrayList<>();
        ArrayList<String> mergedKnown = new ArrayList<>();
        if (existing != null) {
            mergedInstallable.addAll(existing.installablePackages());
            mergedKnown.addAll(existing.allKnownPackages());
        }
        if (descriptor != null) {
            for (String packageKey : descriptor.installablePackages()) {
                if (!containsIgnoreCase(mergedInstallable, packageKey)) {
                    mergedInstallable.add(packageKey);
                }
            }
            for (String packageKey : descriptor.allPackages()) {
                if (!containsIgnoreCase(mergedKnown, packageKey)) {
                    mergedKnown.add(packageKey);
                }
            }
        }
        for (String packageKey : packageKeys) {
            if (!containsIgnoreCase(mergedKnown, packageKey)) {
                mergedKnown.add(packageKey);
            }
            if (!containsIgnoreCase(mergedInstallable, packageKey) && descriptor != null) {
                mergedInstallable.add(packageKey);
            }
        }
        return new KnownBundle(
                bundleId,
                sourceZipName != null && !sourceZipName.isBlank()
                        ? sourceZipName
                        : existing != null ? existing.sourceZipName() : bundleId,
                descriptor != null ? descriptor : existing != null ? existing.archiveDescriptor() : null,
                mergedInstallable,
                mergedKnown
        );
    }

    private BundleStatusView buildStatusView(KnownBundle knownBundle) {
        BundlePreference preference = loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName());
        TreeSet<String> allPackages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        allPackages.addAll(knownBundle.allKnownPackages());
        allPackages.addAll(listInstalledPackageKeys(knownBundle.bundleId()));
        allPackages.addAll(preference.getDisabledPackages());
        if (knownBundle.archiveDescriptor() != null) {
            allPackages.addAll(knownBundle.archiveDescriptor().installablePackages());
        }

        ArrayList<BundlePackageView> packageViews = new ArrayList<>(allPackages.size());
        int successCount = 0;
        int failedCount = 0;
        int disabledCount = 0;
        if (knownBundle.archiveDescriptor() != null) {
            for (BundleVariantGroup variantGroup : knownBundle.archiveDescriptor().variantGroups()) {
                for (BundleVariantOption option : variantGroup.options()) {
                    allPackages.removeIf(packageKey -> packageKey.equalsIgnoreCase(option.packageKey()));
                }

                String selectedPackage = preference.getSelectedPackages().get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
                if (selectedPackage == null && !variantGroup.options().isEmpty()) {
                    selectedPackage = variantGroup.options().get(0).packageKey();
                }

                BundlePackageState state = resolvePackageState(
                        knownBundle,
                        preference,
                        selectedPackage
                );
                switch (state) {
                    case SUCCESS -> successCount++;
                    case FAILED -> failedCount++;
                    case DISABLED -> disabledCount++;
                    case UNSUPPORTED -> {
                    }
                }
                packageViews.add(new BundlePackageView(
                        selectedPackage != null ? selectedPackage : variantGroup.pluginKey(),
                        variantGroup.pluginKey() + " [" + variantGroup.options().size() + "]",
                        state
                ));
            }
        }

        for (String packageKey : allPackages) {
            BundlePackageState state = resolvePackageState(knownBundle, preference, packageKey);
            if (state == BundlePackageState.UNSUPPORTED) {
                continue;
            }
            switch (state) {
                case SUCCESS -> successCount++;
                case FAILED -> failedCount++;
                case DISABLED -> disabledCount++;
                case UNSUPPORTED -> {
                }
            }
            packageViews.add(new BundlePackageView(packageKey, displayPackageName(knownBundle.archiveDescriptor(), packageKey), state));
        }

        BundleOverallState overallState;
        if (packageViews.isEmpty()) {
            overallState = BundleOverallState.FAILED;
        } else if (disabledCount == packageViews.size()) {
            overallState = BundleOverallState.DISABLED;
        } else if (successCount == packageViews.size()) {
            overallState = BundleOverallState.SUCCESS;
        } else if (failedCount == packageViews.size()) {
            overallState = BundleOverallState.FAILED;
        } else {
            overallState = BundleOverallState.PARTIAL;
        }

        packageViews.sort(Comparator.comparing(BundlePackageView::getPackageKey, String.CASE_INSENSITIVE_ORDER));
        return new BundleStatusView(knownBundle.bundleId(), knownBundle.sourceZipName(), packageViews, overallState);
    }

    private List<String> listCommandPackageKeys(KnownBundle knownBundle) {
        TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String packageKey : knownBundle.allKnownPackages()) {
            values.add(basePluginKey(packageKey));
        }
        for (String packageKey : listInstalledPackageKeys(knownBundle.bundleId())) {
            values.add(basePluginKey(packageKey));
        }
        for (String packageKey : loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName()).getDisabledPackages()) {
            values.add(basePluginKey(packageKey));
        }
        if (knownBundle.archiveDescriptor() != null) {
            for (String packageKey : knownBundle.archiveDescriptor().installablePackages()) {
                values.add(basePluginKey(packageKey));
            }
        }
        return new ArrayList<>(values);
    }

    private String selectRequestedPackageKey(
            BundleArchiveDescriptor descriptor,
            BundlePreference preference,
            List<String> matchingPackageKeys,
            boolean autoSelect,
            List<String> messages
    ) throws BundleException {
        if (matchingPackageKeys.size() <= 1) {
            return matchingPackageKeys.get(0);
        }

        BundleVariantGroup variantGroup = findVariantGroup(descriptor, matchingPackageKeys);
        if (variantGroup == null) {
            return matchingPackageKeys.get(0);
        }

        String selectedPackage = preference.getSelectedPackages().get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
        if (selectedPackage != null && containsIgnoreCase(matchingPackageKeys, selectedPackage)) {
            return resolvePackageKey(matchingPackageKeys, selectedPackage);
        }

        VariantChoiceGroup bundleChoiceGroup = findBundleChoiceGroup(descriptor);
        VariantChoiceOption selectedBundleOption = bundleChoiceGroup == null
                ? null
                : findMatchingBundleChoiceOption(bundleChoiceGroup, preference.getSelectedBundleVariant());
        if (selectedBundleOption != null) {
            String bundleSelectedPackage = selectedBundleOption.selections().get(variantGroup.pluginKey().toLowerCase(Locale.ROOT));
            if (bundleSelectedPackage != null && containsIgnoreCase(matchingPackageKeys, bundleSelectedPackage)) {
                return resolvePackageKey(matchingPackageKeys, bundleSelectedPackage);
            }
        }

        if (!autoSelect) {
            throw new BundleException("Package requires a selected variant: " + variantGroup.pluginKey());
        }

        String selectedByDefault = matchingPackageKeys.get(0);
        BundleVariantOption selectedOption = findVariantOption(variantGroup, selectedByDefault);
        if (selectedOption != null) {
            messages.add("Auto-selected variant: " + variantGroup.pluginKey() + " - " + selectedOption.displayName());
        }
        return selectedByDefault;
    }

    private BundleVariantGroup findVariantGroup(BundleArchiveDescriptor descriptor, List<String> matchingPackageKeys) {
        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            if (matchingPackageKeys.stream().allMatch(candidate ->
                    variantGroup.options().stream().anyMatch(option -> option.packageKey().equalsIgnoreCase(candidate)))) {
                return variantGroup;
            }
        }
        return null;
    }

    private List<String> toCommandPackageNames(List<String> packageKeys) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String packageKey : packageKeys) {
            names.add(basePluginKey(packageKey));
        }
        return new ArrayList<>(names);
    }

    private BundlePackageState resolvePackageState(KnownBundle knownBundle, BundlePreference preference, String packageKey) {
        boolean nonSelectedVariant = knownBundle.archiveDescriptor() != null
                && isNonSelectedVariant(knownBundle.archiveDescriptor(), preference, packageKey);
        if (preference.isBundleDisabled()
                || containsIgnoreCase(preference.getDisabledPackages(), packageKey)
                || nonSelectedVariant) {
            return BundlePackageState.DISABLED;
        }
        if (containsIgnoreCase(listInstalledPackageKeys(knownBundle.bundleId()), packageKey)) {
            return BundlePackageState.SUCCESS;
        }
        if (!containsIgnoreCase(knownBundle.installablePackages(), packageKey)) {
            return BundlePackageState.UNSUPPORTED;
        }
        if (hasPackageFailure(knownBundle.bundleId(), packageKey)) {
            return BundlePackageState.FAILED;
        }
        return BundlePackageState.FAILED;
    }

    private List<BundleArchiveDescriptor> scanIncomingBundleDescriptors(boolean logFailures) {
        File[] files = listIncomingBundleSources();
        if (files == null || files.length == 0) {
            return List.of();
        }

        ArrayList<BundleArchiveDescriptor> descriptors = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                String bundleId = stateStore.computeBundleId(file, plugin.getLogger());
                String sourceSha1 = computeSourceSha1(file);
                ArchivePackageInfo packageInfo = inspectArchivePackages(file);
                BundleArchiveDescriptor descriptor = new BundleArchiveDescriptor(
                        bundleId,
                        file.getName(),
                        sourceSha1,
                        packageInfo.installablePackages(),
                        packageInfo.allPackages(),
                        packageInfo.variantGroups(),
                        packageInfo.variantChoiceGroups(),
                        List.of(),
                        List.of()
                );
                descriptors.add(new BundleArchiveDescriptor(
                        descriptor.bundleId(),
                        descriptor.sourceZipName(),
                        descriptor.sourceSha1(),
                        descriptor.installablePackages(),
                        descriptor.allPackages(),
                        descriptor.variantGroups(),
                        descriptor.variantChoiceGroups(),
                        buildBundleScanWarnings(descriptor, packageInfo.packageDescriptors()),
                        discoverMissingPluginKeys(packageInfo.packageDescriptors())
                ));
            } catch (BundleException | IOException ex) {
                if (logFailures) {
                    plugin.getLogger().warning("Failed to inspect bundle " + file.getName() + ": " + ex.getMessage());
                }
            }
        }
        descriptors.sort(Comparator.comparing(BundleArchiveDescriptor::sourceZipName, String.CASE_INSENSITIVE_ORDER));
        return descriptors;
    }

    private ArchivePackageInfo inspectArchivePackages(File archiveFile) throws BundleException, IOException {
        try (BundleArchive archive = openArchive(archiveFile)) {
            return archiveInspector.inspectArchivePackages(archive, installedServerPluginNames());
        }
    }

    private List<BundlePackageDescriptor> discoverPackageDescriptors(BundleArchive archive) throws BundleException {
        return archiveInspector.discoverPackageDescriptors(archive, installedServerPluginNames());
    }

    private String trimPackageRoot(String entryPath, String packageRootPath) throws BundleException {
        String normalizedEntry = PathUtils.normalizeZipPath(entryPath);
        if (packageRootPath == null || packageRootPath.isBlank()) {
            return normalizedEntry;
        }
        String normalizedRoot = PathUtils.normalizeRelativePath(packageRootPath);
        String prefix = normalizedRoot + "/";
        if (!normalizedEntry.startsWith(prefix)) {
            throw new BundleException("Bundle entry is outside detected package root: " + entryPath);
        }
        return PathUtils.normalizeRelativePath(normalizedEntry.substring(prefix.length()));
    }

    private BundlePreference loadPreference(String bundleId, String sourceZipName) {
        return stateStore.loadPreference(bundleId, sourceZipName);
    }

    private List<BundlePreference> loadAllPreferences() {
        return stateStore.loadAllPreferences();
    }

    private void savePreference(BundlePreference preference) throws BundleException {
        stateStore.savePreference(preference);
    }

    private boolean preferenceExists(String bundleId) {
        return stateStore.preferenceExists(bundleId);
    }

    private void deletePreference(String bundleId) throws BundleException {
        stateStore.deletePreference(bundleId);
    }

    private BundlePreference updateStoredBundleMetadata(
            BundlePreference preference,
            BundleArchiveDescriptor descriptor
    ) {
        return new BundlePreference(
                preference.getBundleId(),
                descriptor.sourceZipName(),
                descriptor.sourceSha1(),
                preference.isBundleDisabled(),
                preference.getDisabledPackages(),
                preference.getSelectedBundleVariant(),
                preference.getSelectedPackages()
        );
    }

    private boolean hasPackageFailure(String bundleId, String packageKey) {
        Map<String, String> packageFailures = failedPackageMessages.get(bundleId);
        if (packageFailures == null) {
            return false;
        }
        return packageFailures.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(packageKey));
    }

    private void recordBundleFailure(String bundleId, String packageKey, String message) {
        failedPackageMessages
                .computeIfAbsent(bundleId, ignored -> new LinkedHashMap<>())
                .put(packageKey.toLowerCase(Locale.ROOT), message);
    }

    private void clearBundleFailures(String bundleId) {
        failedPackageMessages.remove(bundleId);
    }

    private void clearPackageFailure(String bundleId, String packageKey) {
        Map<String, String> packageFailures = failedPackageMessages.get(bundleId);
        if (packageFailures == null) {
            return;
        }
        packageFailures.remove(packageKey.toLowerCase(Locale.ROOT));
        if (packageFailures.isEmpty()) {
            failedPackageMessages.remove(bundleId);
        }
    }

    private void clearPendingVariantPrompt() {
        pendingVariantOptions = List.of();
        pendingVariantMessages = List.of();
    }

    private <T> List<T> deduplicatePackages(List<T> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPathIgnoreCase(Collection<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private int parseBundleOrder(String bundleId) {
        try {
            return Integer.parseInt(bundleId);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean matchesBundleQuery(BundleRecord record, String normalizedQuery) {
        String sourceZipName = record.getSourceZipName().toLowerCase(Locale.ROOT);
        String sourceBaseName = baseName(record.getSourceZipName()).toLowerCase(Locale.ROOT);
        return record.getBundleId().equalsIgnoreCase(normalizedQuery)
                || record.getBundleShortId().equalsIgnoreCase(normalizedQuery)
                || sourceZipName.equals(normalizedQuery)
                || sourceBaseName.equals(normalizedQuery);
    }

    private File resolveBundleArchiveFile(String archiveFileName) throws BundleException {
        if (archiveFileName == null || archiveFileName.isBlank()) {
            throw new BundleException("Bundle file name cannot be empty.");
        }

        String normalizedQuery = archiveFileName.trim();
        File[] files = listIncomingBundleSources();
        if (files == null || files.length == 0) {
            throw new BundleException("No bundle archive files were found in " + incomingBundleDirectory.getName() + ".");
        }

        for (File file : files) {
            if (file.getName().equalsIgnoreCase(normalizedQuery)
                    || baseName(file.getName()).equalsIgnoreCase(normalizedQuery)) {
                return file;
            }
        }

        String zipVariant = normalizedQuery.toLowerCase(Locale.ROOT).endsWith(".zip") ? normalizedQuery : normalizedQuery + ".zip";
        for (File file : files) {
            if (file.getName().equalsIgnoreCase(zipVariant)) {
                return file;
            }
        }

        throw new BundleException("Bundle file not found: " + archiveFileName);
    }

    private List<BundleRecord.ConfigMutation> applyConfigMutations(
            List<BundleRecord.ConfigMutation> mutations,
            List<String> warnings
    ) throws BundleException {
        ArrayList<BundleRecord.ConfigMutation> applied = new ArrayList<>();
        for (BundleRecord.ConfigMutation mutation : mutations) {
            // Persist only mutations that were actually added to avoid removing pre-existing config.
            ConfigMutationResult result = applyConfigMutation(mutation);
            if (result.applied()) {
                applied.add(mutation);
            }
            if (result.warning() != null && !result.warning().isBlank()) {
                warnings.add(result.warning());
            }
        }
        return applied;
    }

    private void validateConfigMutationPlan(
            String packageKey,
            List<BundleRecord.ConfigMutation> mutations
    ) throws BundleException {
        LinkedHashMap<String, String> registeredSections = new LinkedHashMap<>();
        for (BundleRecord.ConfigMutation mutation : mutations) {
            if (mutation.getConfigPath() == null || mutation.getConfigPath().isBlank()) {
                throw new BundleException("Package '" + packageKey + "' contains a config mutation with empty config path.");
            }
            if (mutation.getTargetPath() == null || mutation.getTargetPath().isBlank()) {
                throw new BundleException("Package '" + packageKey + "' contains a config mutation with empty target path.");
            }
            if (mutation.getValue() == null || mutation.getValue().isBlank()) {
                throw new BundleException("Package '" + packageKey + "' contains a config mutation with empty value.");
            }

            switch (mutation.getType()) {
                case APPEND_STRING_LIST -> PathUtils.normalizeRelativePath(mutation.getConfigPath());
                case REGISTER_SECTION_FILE -> {
                    PathUtils.normalizeRelativePath(mutation.getConfigPath());
                    String uniqueTarget = mutation.getConfigPath().toLowerCase(Locale.ROOT)
                            + "|" + mutation.getTargetPath().toLowerCase(Locale.ROOT);
                    String previousValue = registeredSections.putIfAbsent(uniqueTarget, mutation.getValue());
                    if (previousValue != null && !previousValue.equalsIgnoreCase(mutation.getValue())) {
                        throw new BundleException(
                                "Package '" + packageKey + "' tries to register multiple files into the same config node: "
                                        + mutation.getTargetPath()
                        );
                    }
                }
                default -> throw new BundleException("Unsupported config mutation type: " + mutation.getType());
            }
        }
    }

    private ConfigMutationResult applyConfigMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        return switch (mutation.getType()) {
            case APPEND_STRING_LIST -> applyAppendStringListMutation(mutation);
            case REGISTER_SECTION_FILE -> applyRegisterSectionFileMutation(mutation);
            default -> throw new BundleException("Unsupported config mutation type: " + mutation.getType());
        };
    }

    private ConfigMutationResult applyAppendStringListMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        Path configPath = resolveServerPath(mutation.getConfigPath());
        YamlConfiguration config = loadYaml(configPath);
        List<String> candidatePaths = splitTargetPaths(mutation.getTargetPath());
        String selectedPath = candidatePaths.get(0);
        for (String candidatePath : candidatePaths) {
            if (config.isSet(candidatePath)) {
                selectedPath = candidatePath;
                break;
            }
        }

        List<String> values = new ArrayList<>(config.getStringList(selectedPath));
        if (values.contains(mutation.getValue())) {
            return new ConfigMutationResult(false, null);
        }

        values.add(mutation.getValue());
        config.set(selectedPath, values);
        saveYaml(config, configPath);
        return new ConfigMutationResult(true, null);
    }

    private ConfigMutationResult applyRegisterSectionFileMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        Path configPath = resolveServerPath(mutation.getConfigPath());
        YamlConfiguration config = loadYaml(configPath);
        ConfigurationSection existing = config.getConfigurationSection(mutation.getTargetPath());
        if (existing != null) {
            String currentFile = existing.getString("file");
            if (mutation.getValue().equals(currentFile)) {
                return new ConfigMutationResult(false, null);
            }
            return new ConfigMutationResult(false, "Config path already exists: " + mutation.getTargetPath());
        }

        config.set(mutation.getTargetPath() + ".file", mutation.getValue());
        saveYaml(config, configPath);
        return new ConfigMutationResult(true, null);
    }

    private void rollbackConfigMutations(List<BundleRecord.ConfigMutation> mutations) throws BundleException {
        List<BundleRecord.ConfigMutation> reversed = new ArrayList<>(mutations);
        reversed.sort(Comparator.comparingInt((BundleRecord.ConfigMutation mutation) -> mutation.getTargetPath().length()).reversed());
        for (BundleRecord.ConfigMutation mutation : reversed) {
            rollbackConfigMutation(mutation);
        }
    }

    private void rollbackConfigMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        switch (mutation.getType()) {
            case APPEND_STRING_LIST -> rollbackAppendStringListMutation(mutation);
            case REGISTER_SECTION_FILE -> rollbackRegisterSectionFileMutation(mutation);
            default -> throw new BundleException("Unsupported config mutation type: " + mutation.getType());
        }
    }

    private void rollbackAppendStringListMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        Path configPath = resolveServerPath(mutation.getConfigPath());
        if (!Files.exists(configPath)) {
            return;
        }

        YamlConfiguration config = loadYaml(configPath);
        boolean changed = false;
        for (String candidatePath : splitTargetPaths(mutation.getTargetPath())) {
            List<String> values = new ArrayList<>(config.getStringList(candidatePath));
            if (!values.removeIf(value -> value.equals(mutation.getValue()))) {
                continue;
            }

            config.set(candidatePath, values);
            changed = true;
        }

        if (changed) {
            saveYaml(config, configPath);
        }
    }

    private void rollbackRegisterSectionFileMutation(BundleRecord.ConfigMutation mutation) throws BundleException {
        Path configPath = resolveServerPath(mutation.getConfigPath());
        if (!Files.exists(configPath)) {
            return;
        }

        YamlConfiguration config = loadYaml(configPath);
        ConfigurationSection section = config.getConfigurationSection(mutation.getTargetPath());
        if (section == null) {
            return;
        }

        String currentFile = section.getString("file");
        if (!mutation.getValue().equals(currentFile)) {
            return;
        }

        if (section.getKeys(false).size() <= 1) {
            config.set(mutation.getTargetPath(), null);
        } else {
            config.set(mutation.getTargetPath() + ".file", null);
        }

        saveYaml(config, configPath);
    }

    private void rollbackInstall(
            List<BundleRecord.InstalledFile> installedFiles,
            Collection<String> createdDirectories
    ) throws BundleException {
        deleteInstalledFiles(installedFiles);
        restoreBackups(installedFiles);
        deleteCreatedDirectories(createdDirectories);
    }

    private void deleteInstalledFiles(List<BundleRecord.InstalledFile> installedFiles) throws BundleException {
        List<BundleRecord.InstalledFile> reversedFiles = new ArrayList<>(installedFiles);
        reversedFiles.sort(Comparator.comparingInt((BundleRecord.InstalledFile file) -> file.getTargetPath().length()).reversed());
        for (BundleRecord.InstalledFile installedFile : reversedFiles) {
            deletePathOrThrow(
                    resolveServerPath(installedFile.getTargetPath()),
                    "installed file " + installedFile.getTargetPath()
            );
        }
    }

    private void restoreBackups(List<BundleRecord.InstalledFile> installedFiles) throws BundleException {
        for (BundleRecord.InstalledFile installedFile : installedFiles) {
            if (installedFile.getBackupPath() == null || installedFile.getBackupPath().isBlank()) {
                continue;
            }

            Path backupPath = backupDirectory.toPath().resolve(installedFile.getBackupPath()).normalize();
            if (!Files.exists(backupPath)) {
                continue;
            }

            Path targetPath = resolveServerPath(installedFile.getTargetPath());
            try {
                createDirectory(targetPath.getParent());
                Files.copy(backupPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ex) {
                throw new BundleException("Failed to restore backup for " + installedFile.getTargetPath() + ".", ex);
            }
        }
    }

    private void deleteCreatedDirectories(Collection<String> createdDirectories) throws BundleException {
        List<String> ordered = new ArrayList<>(createdDirectories);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        for (String relativeDirectory : ordered) {
            Path directoryPath = resolveServerPath(relativeDirectory);
            try {
                Files.deleteIfExists(directoryPath);
            } catch (DirectoryNotEmptyException ignored) {
                // Leave the directory alone if other files still use it.
            } catch (IOException ex) {
                throw new BundleException("Failed to delete directory " + relativeDirectory + ".", ex);
            }
        }
    }

    private void cleanupFailedInstallArtifacts(String recordId) {
        deleteIfExists(stateStore.getRecordFile(recordId).toPath());
        deleteRecursively(backupDirectory.toPath().resolve(recordId));
    }

    private void cleanupUninstalledRecord(String recordId) throws BundleException {
        deletePathOrThrow(stateStore.getRecordFile(recordId).toPath(), "bundle record " + recordId);
        deleteRecursivelyOrThrow(backupDirectory.toPath().resolve(recordId), "backup directory for " + recordId);
    }

    private BundleStateSnapshot captureBundleState(String bundleId, String sourceZipName) throws BundleException {
        BundlePreference preference = loadPreference(bundleId, sourceZipName);
        boolean preferenceExists = preferenceExists(bundleId);
        List<BundleRecord> records = new ArrayList<>(findBundleRecords(bundleId));
        LinkedHashMap<String, String> packageFailures = new LinkedHashMap<>();
        Map<String, String> currentFailures = failedPackageMessages.get(bundleId);
        if (currentFailures != null) {
            packageFailures.putAll(currentFailures);
        }

        Path snapshotDirectory = null;
        if (!records.isEmpty()) {
            snapshotDirectory = backupDirectory.toPath()
                    .resolve("_transactions")
                    .resolve(bundleId + "-" + UUID.randomUUID());
            createDirectory(snapshotDirectory);
            for (BundleRecord record : records) {
                snapshotRecordState(record, snapshotDirectory);
            }
        }

        return new BundleStateSnapshot(
                bundleId,
                preference,
                preferenceExists,
                new ArrayList<>(records),
                packageFailures,
                snapshotDirectory
        );
    }

    private void snapshotRecordState(BundleRecord record, Path snapshotDirectory) throws BundleException {
        for (BundleRecord.InstalledFile installedFile : record.getInstalledFiles()) {
            Path targetPath = resolveServerPath(installedFile.getTargetPath());
            if (!Files.exists(targetPath)) {
                continue;
            }
            Path snapshotPath = snapshotInstalledFilePath(snapshotDirectory, record.getId(), installedFile.getTargetPath());
            copyRecursivelyOrThrow(targetPath, snapshotPath, "installed file snapshot " + installedFile.getTargetPath());
        }

        Path backupPath = backupDirectory.toPath().resolve(record.getId());
        if (Files.exists(backupPath)) {
            copyRecursivelyOrThrow(
                    backupPath,
                    snapshotBackupDirectoryPath(snapshotDirectory, record.getId()),
                    "backup snapshot for " + record.getId()
            );
        }
    }

    private void restoreBundleState(BundleStateSnapshot snapshot) throws BundleException {
        List<BundleRecord> currentRecords = findBundleRecords(snapshot.bundleId());
        currentRecords.sort(Comparator.comparing(BundleRecord::getPackageKey).reversed());
        for (BundleRecord currentRecord : currentRecords) {
            uninstallPackage(currentRecord);
        }

        for (BundleRecord record : snapshot.records()) {
            restoreRecordState(record, snapshot.snapshotDirectory());
        }

        restorePreferenceSnapshot(snapshot);
        restorePackageFailures(snapshot);
    }

    private void restoreRecordState(BundleRecord record, Path snapshotDirectory) throws BundleException {
        if (snapshotDirectory != null) {
            Path currentBackupPath = backupDirectory.toPath().resolve(record.getId());
            deleteRecursivelyOrThrow(currentBackupPath, "backup directory for " + record.getId());

            Path backupSnapshotPath = snapshotBackupDirectoryPath(snapshotDirectory, record.getId());
            if (Files.exists(backupSnapshotPath)) {
                copyRecursivelyOrThrow(
                        backupSnapshotPath,
                        currentBackupPath,
                        "backup restore for " + record.getId()
                );
            }

            for (BundleRecord.InstalledFile installedFile : record.getInstalledFiles()) {
                Path sourceSnapshot = snapshotInstalledFilePath(snapshotDirectory, record.getId(), installedFile.getTargetPath());
                if (!Files.exists(sourceSnapshot)) {
                    continue;
                }

                Path targetPath = resolveServerPath(installedFile.getTargetPath());
                createDirectory(targetPath.getParent());
                copyRecursivelyOrThrow(sourceSnapshot, targetPath, "installed file restore " + installedFile.getTargetPath());
            }
        }

        ArrayList<String> warnings = new ArrayList<>();
        List<BundleRecord.ConfigMutation> restoredMutations = applyConfigMutations(record.getConfigMutations(), warnings);
        if (restoredMutations.size() != record.getConfigMutations().size() || !warnings.isEmpty()) {
            throw new BundleException("Failed to restore config mutations for package '" + record.getPackageKey() + "'.");
        }

        stateStore.saveRecord(record);
    }

    private void restorePreferenceSnapshot(BundleStateSnapshot snapshot) throws BundleException {
        if (snapshot.preferenceExists()) {
            savePreference(snapshot.preference());
            return;
        }

        deletePreference(snapshot.bundleId());
    }

    private void restorePackageFailures(BundleStateSnapshot snapshot) {
        if (snapshot.packageFailures().isEmpty()) {
            failedPackageMessages.remove(snapshot.bundleId());
            return;
        }

        failedPackageMessages.put(snapshot.bundleId(), new LinkedHashMap<>(snapshot.packageFailures()));
    }

    private void cleanupBundleStateSnapshot(BundleStateSnapshot snapshot) {
        if (snapshot.snapshotDirectory() == null) {
            return;
        }
        deleteRecursively(snapshot.snapshotDirectory());
    }

    private Path snapshotInstalledFilePath(Path snapshotDirectory, String recordId, String targetPath) throws BundleException {
        return snapshotDirectory.resolve("files")
                .resolve(recordId)
                .resolve(PathUtils.normalizeRelativePath(targetPath))
                .normalize();
    }

    private Path snapshotBackupDirectoryPath(Path snapshotDirectory, String recordId) {
        return snapshotDirectory.resolve("backups").resolve(recordId).normalize();
    }

    private <T> T withBundleStateRollback(
            BundleStateSnapshot snapshot,
            boolean touchesInstalledState,
            BundleStateOperation<T> operation
    ) throws BundleException {
        boolean cleanupSnapshot = false;
        try {
            T result = operation.run();
            cleanupSnapshot = true;
            return result;
        } catch (BundleException | RuntimeException ex) {
            BundleException failure = asBundleException(ex, "Bundle operation failed.");
            try {
                if (touchesInstalledState) {
                    restoreBundleState(snapshot);
                } else {
                    restorePreferenceSnapshot(snapshot);
                    restorePackageFailures(snapshot);
                }
                cleanupSnapshot = true;
            } catch (BundleException rollbackException) {
                BundleException combined = new BundleException(
                        failure.getMessage() + " Automatic rollback failed: " + rollbackException.getMessage(),
                        failure
                );
                combined.addSuppressed(rollbackException);
                throw combined;
            }
            throw failure;
        } finally {
            if (cleanupSnapshot) {
                cleanupBundleStateSnapshot(snapshot);
            }
        }
    }

    private void ensureRequiredPackagesInstalled(
            String bundleId,
            List<String> requiredPackages,
            BundleActionReport report,
            String failurePrefix
    ) throws BundleException {
        if (requiredPackages.isEmpty()) {
            return;
        }

        List<String> installedPackages = listInstalledPackageKeys(bundleId);
        ArrayList<String> missingPackages = new ArrayList<>();
        for (String requiredPackage : requiredPackages) {
            if (!containsIgnoreCase(installedPackages, requiredPackage)) {
                missingPackages.add(requiredPackage);
            }
        }

        if (missingPackages.isEmpty()) {
            return;
        }

        String details = report.getMessages().isEmpty()
                ? ""
                : " " + report.getMessages().get(report.getMessages().size() - 1);
        throw new BundleException(
                failurePrefix + " Missing packages: " + String.join(", ", deduplicatePackages(missingPackages)) + "." + details
        );
    }

    private boolean hasBundleMutationWork(
            String bundleId,
            BundleArchiveDescriptor descriptor,
            List<String> targetPackages
    ) {
        List<String> installedPackages = listInstalledPackageKeys(bundleId);
        for (String targetPackage : targetPackages) {
            if (!containsIgnoreCase(installedPackages, targetPackage)) {
                return true;
            }
        }

        for (BundleVariantGroup variantGroup : descriptor.variantGroups()) {
            String selectedPackage = targetPackages.stream()
                    .filter(target -> variantGroup.options().stream().anyMatch(option -> option.packageKey().equalsIgnoreCase(target)))
                    .findFirst()
                    .orElse(null);
            for (BundleVariantOption option : variantGroup.options()) {
                if (selectedPackage != null && option.packageKey().equalsIgnoreCase(selectedPackage)) {
                    continue;
                }
                if (containsIgnoreCase(installedPackages, option.packageKey())) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<String> determineCriticalPackages(
            BundleArchiveDescriptor descriptor,
            List<String> targetPackages,
            List<String> installedPackages
    ) {
        ArrayList<String> criticalPackages = new ArrayList<>();
        for (String targetPackage : targetPackages) {
            if (containsIgnoreCase(installedPackages, targetPackage)) {
                criticalPackages.add(targetPackage);
                continue;
            }

            for (String installedPackage : installedPackages) {
                if (basePluginKey(installedPackage).equalsIgnoreCase(basePluginKey(targetPackage))) {
                    criticalPackages.add(targetPackage);
                    break;
                }
            }
        }
        return deduplicatePackages(criticalPackages);
    }

    private BundleException rollbackBatchInstallFailure(
            List<BundleInstallResult> installedResults,
            Throwable failure
    ) {
        BundleException bundleException = asBundleException(failure, "Failed to install bundle.");
        if (installedResults.isEmpty()) {
            return bundleException;
        }

        try {
            rollbackInstalledResults(installedResults);
            return bundleException;
        } catch (BundleException rollbackException) {
            BundleException combined = new BundleException(
                    bundleException.getMessage()
                            + " Automatic rollback of earlier packages failed: "
                            + rollbackException.getMessage(),
                    bundleException
            );
            combined.addSuppressed(rollbackException);
            return combined;
        }
    }

    private BundleException rollbackFailedPackageInstall(
            String packageKey,
            String recordId,
            List<BundleRecord.ConfigMutation> appliedMutations,
            List<BundleRecord.InstalledFile> installedFiles,
            Collection<String> createdDirectories,
            BundleException failure
    ) {
        boolean rollbackSucceeded = false;
        try {
            rollbackConfigMutations(appliedMutations);
            rollbackInstall(installedFiles, createdDirectories);
            rollbackSucceeded = true;
            return failure;
        } catch (BundleException rollbackException) {
            BundleException combined = new BundleException(
                    "Failed to install package '" + packageKey + "' and automatic rollback was incomplete: "
                            + rollbackException.getMessage(),
                    failure
            );
            combined.addSuppressed(rollbackException);
            return combined;
        } finally {
            if (rollbackSucceeded) {
                cleanupFailedInstallArtifacts(recordId);
            }
        }
    }

    private void rollbackInstalledResults(List<BundleInstallResult> installedResults) throws BundleException {
        List<BundleInstallResult> reversedResults = new ArrayList<>(installedResults);
        Collections.reverse(reversedResults);
        for (BundleInstallResult installedResult : reversedResults) {
            uninstallPackage(installedResult.getRecord());
        }
    }

    private BundleException asBundleException(Throwable failure, String fallbackMessage) {
        if (failure instanceof BundleException bundleException) {
            return bundleException;
        }

        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = fallbackMessage;
        }
        return new BundleException(message, failure);
    }

    private void deletePathOrThrow(Path path, String description) throws BundleException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new BundleException("Failed to delete " + description + ".", ex);
        }
    }

    private void deleteRecursivelyOrThrow(Path path, String description) throws BundleException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            List<Path> paths = Files.walk(path).sorted(Comparator.reverseOrder()).toList();
            for (Path current : paths) {
                Files.deleteIfExists(current);
            }
        } catch (IOException ex) {
            throw new BundleException("Failed to delete " + description + ".", ex);
        }
    }

    private void copyRecursivelyOrThrow(Path source, Path target, String description) throws BundleException {
        if (source == null || !Files.exists(source)) {
            return;
        }

        try {
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    for (Path current : paths.toList()) {
                        Path relative = source.relativize(current);
                        Path destination = relative.getNameCount() == 0 ? target : target.resolve(relative);
                        if (Files.isDirectory(current)) {
                            Files.createDirectories(destination);
                            continue;
                        }

                        createDirectory(destination.getParent());
                        Files.copy(current, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
                return;
            }

            createDirectory(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException ex) {
            throw new BundleException("Failed to copy " + description + ".", ex);
        }
    }

    private void mergeConfigMutations(
            List<BundleRecord.ConfigMutation> target,
            List<BundleRecord.ConfigMutation> source
    ) {
        LinkedHashMap<String, BundleRecord.ConfigMutation> unique = new LinkedHashMap<>();
        for (BundleRecord.ConfigMutation mutation : target) {
            unique.put(mutation.uniqueKey(), mutation);
        }
        for (BundleRecord.ConfigMutation mutation : source) {
            unique.putIfAbsent(mutation.uniqueKey(), mutation);
        }

        target.clear();
        target.addAll(unique.values());
    }

    private BundleArchive openArchive(File archiveFile) throws BundleException {
        try {
            if (archiveFile.isDirectory()) {
                return new DirectoryBundleArchive(archiveFile.toPath());
            }
            String lowerName = archiveFile.getName().toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".zip")) {
                return new ZipBundleArchive(archiveFile);
            }
        } catch (IOException ex) {
            throw new BundleException("Failed to open bundle archive: " + archiveFile.getName(), ex);
        }

        throw new BundleException("Unsupported bundle archive type: " + archiveFile.getName());
    }

    private File[] listIncomingBundleSources() {
        return incomingBundleDirectory.listFiles(this::isSupportedIncomingSource);
    }

    private boolean isSupportedIncomingSource(File file) {
        return file.isDirectory() || isSupportedArchiveName(file.getName());
    }

    private boolean isSupportedArchiveName(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".zip");
    }

    private Path resolveServerPath(String relativePath) {
        try {
            Path resolved = serverRoot.resolve(PathUtils.normalizeRelativePath(relativePath)).normalize();
            if (!resolved.startsWith(serverRoot)) {
                throw new BundleException("Resolved path escapes server root: " + relativePath);
            }
            return resolved;
        } catch (BundleException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private void ensureParentDirectories(Path directory, LinkedHashSet<String> createdDirectories) throws IOException, BundleException {
        if (directory == null) {
            return;
        }

        ArrayList<Path> missingDirectories = new ArrayList<>();
        Path current = directory;
        while (current != null && !Files.exists(current)) {
            if (!current.startsWith(serverRoot)) {
                throw new BundleException("Target directory escapes server root: " + current);
            }
            missingDirectories.add(current);
            current = current.getParent();
        }

        for (int i = missingDirectories.size() - 1; i >= 0; i--) {
            Path missing = missingDirectories.get(i);
            Files.createDirectory(missing);
            createdDirectories.add(toServerRelativePath(missing));
        }
    }

    private String toServerRelativePath(Path path) {
        return serverRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void createDirectory(Path directory) {
        if (directory == null) {
            return;
        }

        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create directory " + directory, ex);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to delete " + path + ": " + ex.getMessage());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            List<Path> paths = Files.walk(path).sorted(Comparator.reverseOrder()).toList();
            for (Path current : paths) {
                Files.deleteIfExists(current);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to delete " + path + ": " + ex.getMessage());
        }
    }

    private YamlConfiguration loadYaml(Path configPath) {
        return YamlConfiguration.loadConfiguration(configPath.toFile());
    }

    private void saveYaml(YamlConfiguration configuration, Path configPath) throws BundleException {
        createDirectory(configPath.getParent());
        try {
            configuration.save(configPath.toFile());
        } catch (IOException ex) {
            throw new BundleException("Failed to save config " + configPath + ".", ex);
        }
    }

    private String shortId(String value) {
        return value;
    }

    private Path deriveServerRoot(File dataFolder) {
        File absoluteDataFolder = dataFolder.getAbsoluteFile();
        File current = absoluteDataFolder;
        while (current != null) {
            if ("plugins".equalsIgnoreCase(current.getName())) {
                File parent = current.getParentFile();
                if (parent != null) {
                    return parent.toPath().toAbsolutePath().normalize();
                }
                break;
            }
            current = current.getParentFile();
        }
        return absoluteDataFolder.toPath().toAbsolutePath().normalize();
    }

    private String recordId(String bundleId, String packageKey) {
        return bundleId + "@" + packageKey.toLowerCase(Locale.ROOT);
    }

    private String packageInstallPrefix(String bundleIdShort, String packageKey) {
        int variantSeparator = packageKey.indexOf('@');
        if (variantSeparator < 0 || variantSeparator >= packageKey.length() - 1) {
            return bundleIdShort;
        }

        String variantKey = packageKey.substring(variantSeparator + 1).replace('+', '_');
        return bundleIdShort + "_" + variantKey;
    }

    private List<String> splitTargetPaths(String rawTargetPath) {
        String[] parts = rawTargetPath.split("\\|\\|");
        ArrayList<String> targetPaths = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                targetPaths.add(part);
            }
        }
        return targetPaths.isEmpty() ? List.of(rawTargetPath) : targetPaths;
    }

    private String baseName(String fileName) {
        return PathUtils.baseName(fileName);
    }

    private String computeSourceSha1(File source) throws BundleException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            if (source.isDirectory()) {
                updateDirectoryDigest(digest, source.toPath(), source.toPath());
            } else {
                try (InputStream inputStream = Files.newInputStream(source.toPath())) {
                    updateDigest(digest, inputStream);
                }
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new BundleException("Failed to compute SHA-1 for bundle source " + source.getName() + ".", ex);
        }
    }

    private void updateDirectoryDigest(
            MessageDigest digest,
            Path root,
            Path current
    ) throws IOException {
        try (var paths = Files.walk(current)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relativePath = root.relativize(path).toString().replace('\\', '/');
                digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream inputStream = Files.newInputStream(path)) {
                    updateDigest(digest, inputStream);
                }
                digest.update((byte) 0);
            }
        }
    }

    private void updateDigest(MessageDigest digest, InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    @FunctionalInterface
    private interface BundleStateOperation<T> {
        T run() throws BundleException;
    }

    private record BundleStateSnapshot(
            String bundleId,
            BundlePreference preference,
            boolean preferenceExists,
            List<BundleRecord> records,
            Map<String, String> packageFailures,
            Path snapshotDirectory
    ) {
    }

}
