package tk.funayd.bundleManager.bundle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
import tk.funayd.bundleManager.installer.mythiclib.MythicLibInstaller;
import tk.funayd.bundleManager.installer.mythicmobs.MythicMobsInstaller;
import tk.funayd.bundleManager.installer.resourcepack.ResourcePackInstaller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class BundleService {

    private static final String APPEND_STRING_LIST = "APPEND_STRING_LIST";
    private static final String REGISTER_SECTION_FILE = "REGISTER_SECTION_FILE";

    private final JavaPlugin plugin;
    private final InstallerRegistry installerRegistry;
    private final File incomingBundleDirectory;
    private final File dataDirectory;
    private final File packageDirectory;
    private final File preferenceDirectory;
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
                new ItemsAdderInstaller(),
                new DeluxeMenusInstaller()
        ));
        this.incomingBundleDirectory = new File(plugin.getDataFolder(), "bundles");
        this.dataDirectory = new File(plugin.getDataFolder(), "data");
        this.packageDirectory = new File(dataDirectory, "packages");
        this.preferenceDirectory = new File(dataDirectory, "preferences");
        this.backupDirectory = new File(dataDirectory, "backups");
        this.bundleIndexFile = new File(dataDirectory, "bundle-index.yml");
        this.failedPackageMessages = new LinkedHashMap<>();
        this.pendingVariantOptions = List.of();
        this.pendingVariantMessages = List.of();

        File pluginsDirectory = plugin.getDataFolder().getParentFile();
        File root = pluginsDirectory != null && pluginsDirectory.getParentFile() != null
                ? pluginsDirectory.getParentFile()
                : plugin.getDataFolder().getAbsoluteFile();
        this.serverRoot = root.toPath().toAbsolutePath().normalize();
    }

    public void initialize() {
        createDirectory(plugin.getDataFolder().toPath());
        createDirectory(incomingBundleDirectory.toPath());
        createDirectory(dataDirectory.toPath());
        createDirectory(packageDirectory.toPath());
        createDirectory(preferenceDirectory.toPath());
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
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors()) {
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
            TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            values.addAll(knownBundle.allKnownPackages());
            values.addAll(listInstalledPackageKeys(knownBundle.bundleId()));
            values.addAll(loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName()).getDisabledPackages());
            return new ArrayList<>(values);
        } catch (BundleException ex) {
            return List.of();
        }
    }

    public void autoLoadBundles() {
        failedPackageMessages.clear();
        clearPendingVariantPrompt();
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors()) {
            try {
                autoLoadBundle(descriptor);
            } catch (BundleException ex) {
                recordBundleFailure(descriptor.bundleId(), "__bundle__", ex.getMessage());
                plugin.getLogger().warning("Failed to auto-load bundle " + descriptor.sourceZipName() + ": " + ex.getMessage());
            }
        }
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
        BundlePreference preference = loadPreference(knownBundle.bundleId(), knownBundle.sourceZipName());
        LinkedHashMap<String, String> selectedPackages = new LinkedHashMap<>(preference.getSelectedPackages());
        String selectedBundleVariant = preference.getSelectedBundleVariant();
        if ("Bundle".equalsIgnoreCase(option.title())) {
            selectedBundleVariant = option.displayName();
        } else {
            for (Map.Entry<String, String> selection : option.selections().entrySet()) {
                selectedPackages.put(selection.getKey().toLowerCase(Locale.ROOT), selection.getValue());
            }
        }

        ArrayList<String> disabledPackages = new ArrayList<>();
        for (String disabledPackage : preference.getDisabledPackages()) {
            if (option.selections().values().stream().noneMatch(packageKey -> packageKey.equalsIgnoreCase(disabledPackage))) {
                disabledPackages.add(disabledPackage);
            }
        }

        preference = new BundlePreference(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                false,
                disabledPackages,
                selectedBundleVariant,
                selectedPackages
        );
        savePreference(preference);

        VariantSelectionDecision selectionDecision = resolveVariantSelection(
                knownBundle.archiveDescriptor(),
                preference,
                null,
                true
        );
        savePreference(selectionDecision.preference());
        uninstallConflictingVariantPackages(knownBundle.archiveDescriptor(), selectionDecision.targetPackages());
        BundleActionReport report = installEnabledPackages(
                knownBundle.archiveDescriptor(),
                selectionDecision.targetPackages(),
                selectionDecision.preference(),
                false
        );
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
                    false,
                    List.of(),
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
        } else if (preference.isBundleDisabled()) {
            ArrayList<String> disabledPackages = new ArrayList<>();
            for (String installablePackage : knownBundle.installablePackages()) {
                if (!installablePackage.equalsIgnoreCase(requestedPackageKey)) {
                    disabledPackages.add(installablePackage);
                }
            }
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    false,
                    disabledPackages,
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
        } else {
            ArrayList<String> disabledPackages = new ArrayList<>();
            for (String disabledPackage : preference.getDisabledPackages()) {
                if (!disabledPackage.equalsIgnoreCase(requestedPackageKey)) {
                    disabledPackages.add(disabledPackage);
                }
            }
            preference = new BundlePreference(
                    knownBundle.bundleId(),
                    knownBundle.sourceZipName(),
                    false,
                    disabledPackages,
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
        }

        savePreference(preference);
        uninstallConflictingVariantPackages(knownBundle.archiveDescriptor(), targetPackages);
        BundleActionReport report = installEnabledPackages(knownBundle.archiveDescriptor(), targetPackages, preference, true);
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
                    true,
                    List.of(),
                    preference.getSelectedBundleVariant(),
                    preference.getSelectedPackages()
            );
            savePreference(preference);
            for (BundleRecord record : findBundleRecords(knownBundle.bundleId())) {
                removedPackages.add(record.getPackageKey());
            }
            if (!removedPackages.isEmpty()) {
                uninstallBundle(knownBundle.bundleId(), null);
            }
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

        String packageKey = resolvePackageKey(knownBundle.allKnownPackages(), requestedPackageKey);
        ArrayList<String> disabledPackages = new ArrayList<>(preference.getDisabledPackages());
        if (!containsIgnoreCase(disabledPackages, packageKey)) {
            disabledPackages.add(packageKey);
        }
        preference = new BundlePreference(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                false,
                disabledPackages,
                preference.getSelectedBundleVariant(),
                preference.getSelectedPackages()
        );
        savePreference(preference);

        if (containsIgnoreCase(listInstalledPackageKeys(knownBundle.bundleId()), packageKey)) {
            uninstallBundle(knownBundle.bundleId(), packageKey);
            removedPackages.add(packageKey);
        }
        clearPackageFailure(knownBundle.bundleId(), packageKey);

        return new BundleActionReport(
                knownBundle.bundleId(),
                knownBundle.sourceZipName(),
                List.of(),
                List.of(),
                List.of(packageKey),
                List.of()
        );
    }

    public List<BundleStatusView> listBundleStatusViews() {
        LinkedHashMap<String, KnownBundle> bundles = new LinkedHashMap<>();
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors()) {
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
        views.sort(Comparator.comparing(BundleStatusView::getSourceZipName, String.CASE_INSENSITIVE_ORDER));
        return views;
    }

    public List<BundleInstallResult> installBundle(String archiveFileName, String requestedPackageKey) throws BundleException {
        File archiveFile = resolveBundleArchiveFile(archiveFileName);
        String bundleId = computeBundleId(archiveFile);
        String bundleIdShort = shortId(bundleId);

        try (BundleArchive archive = openArchive(archiveFile)) {
            // Ho tro tim package o bat ky do sau nao trong bundle de xu ly variant.
            List<BundlePackageDescriptor> packageDescriptors = discoverPackageDescriptors(archive);
            if (packageDescriptors.isEmpty()) {
                throw new BundleException("Bundle must contain plugin package directories.");
            }

            List<BundlePackageDescriptor> installablePackages = resolveRequestedPackages(packageDescriptors, requestedPackageKey);
            for (BundlePackageDescriptor packageDescriptor : installablePackages) {
                String recordId = recordId(bundleId, packageDescriptor.packageKey());
                if (getRecordFile(recordId).exists()) {
                    throw new BundleException("Package '" + packageDescriptor.packageKey()
                            + "' is already installed for bundle " + bundleIdShort + ".");
                }
            }

            List<String> unsupportedWarnings = new ArrayList<>();
            for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
                if (!packageDescriptor.supported()) {
                    unsupportedWarnings.add("Unsupported package skipped: " + packageDescriptor.packageKey());
                }
            }

            ArrayList<BundleInstallResult> results = new ArrayList<>(installablePackages.size());
            for (BundlePackageDescriptor packageDescriptor : installablePackages) {
                results.add(installPackage(archive, archiveFile.getName(), bundleId, bundleIdShort, packageDescriptor));
            }

            if (results.isEmpty()) {
                throw new BundleException("No supported packages were installed from " + archiveFile.getName() + ".");
            }

            if (!unsupportedWarnings.isEmpty()) {
                BundleInstallResult first = results.get(0);
                ArrayList<String> combinedWarnings = new ArrayList<>(unsupportedWarnings.size() + first.getWarnings().size());
                combinedWarnings.addAll(unsupportedWarnings);
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
        File[] files = packageDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<BundleRecord> records = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                records.add(loadRecord(file));
            } catch (BundleException ex) {
                plugin.getLogger().warning("Skipping broken record " + file.getName() + ": " + ex.getMessage());
            }
        }

        records.sort(Comparator.comparingLong(BundleRecord::getInstalledAtEpochMillis).reversed());
        return records;
    }

    private BundleInstallResult installPackage(
            BundleArchive archive,
            String sourceZipName,
            String bundleId,
            String bundleIdShort,
            BundlePackageDescriptor packageDescriptor
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

            warnings.addAll(validateIdentityConflicts(archive, installer, packageKey, plannedFiles));

            ReferenceRewriteContext rewriteContext = new ReferenceRewriteContext(plannedFiles);
            installPluginEntries(
                    archive,
                    plannedFiles,
                    packageKey,
                    installer,
                    bundleId,
                    createdDirectories,
                    installedFiles,
                    rewriteContext
            );

            mergeConfigMutations(requestedMutations, installer.buildConfigMutations(plannedFiles, installPrefix));
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
            saveRecord(record);
            return new BundleInstallResult(record, warnings);
        } catch (IOException ex) {
            safeRollbackConfigMutations(appliedMutations);
            rollbackInstall(installedFiles, createdDirectories);
            cleanupTransientArtifacts(recordId);
            throw new BundleException("Failed to install package '" + packageKey + "'.", ex);
        } catch (BundleException | RuntimeException ex) {
            safeRollbackConfigMutations(appliedMutations);
            rollbackInstall(installedFiles, createdDirectories);
            cleanupTransientArtifacts(recordId);
            throw ex instanceof BundleException ? (BundleException) ex : new BundleException(
                    "Failed to install package '" + packageKey + "'.",
                    ex
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

        List<BundleRecord.InstalledFile> installedFiles = new ArrayList<>(record.getInstalledFiles());
        installedFiles.sort(Comparator.comparingInt((BundleRecord.InstalledFile file) -> file.getTargetPath().length()).reversed());
        for (BundleRecord.InstalledFile installedFile : installedFiles) {
            deleteIfExists(resolveServerPath(installedFile.getTargetPath()));
        }

        restoreBackups(record.getInstalledFiles());
        deleteCreatedDirectories(record.getCreatedDirectories());
        cleanupTransientArtifacts(record.getId());
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
            LinkedHashSet<String> createdDirectories,
            List<BundleRecord.InstalledFile> installedFiles,
            ReferenceRewriteContext rewriteContext
    ) throws IOException, BundleException {
        for (ResolvedBundleFile plannedFile : plannedFiles) {
            String targetRelativePath = PathUtils.normalizeRelativePath(plannedFile.getTargetRelativePath());
            Path targetPath = resolveServerPath(targetRelativePath);

            // Moi file de target dang ton tai deu duoc backup de uninstall co the phuc hoi.
            ensureParentDirectories(targetPath.getParent(), createdDirectories);

            String backupPath = "";
            if (Files.exists(targetPath)) {
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
                Files.write(targetPath, rewrittenBytes);
            } else {
                try (InputStream inputStream = archive.openInputStream(archiveEntry)) {
                    Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
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

    private List<BundlePackageDescriptor> resolveRequestedPackages(
            List<BundlePackageDescriptor> packageDescriptors,
            String requestedPackageKey
    ) throws BundleException {
        if (requestedPackageKey != null && !requestedPackageKey.isBlank()) {
            for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
                if (packageDescriptor.supported() && packageDescriptor.packageKey().equalsIgnoreCase(requestedPackageKey)) {
                    return List.of(packageDescriptor);
                }
            }
            throw new BundleException("Bundle does not contain package '" + requestedPackageKey + "'.");
        }

        ArrayList<BundlePackageDescriptor> installable = new ArrayList<>();
        for (BundlePackageDescriptor packageDescriptor : packageDescriptors) {
            if (packageDescriptor.supported()) {
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

        ArrayList<BundleRecord> filtered = new ArrayList<>();
        for (BundleRecord record : records) {
            if (record.getPackageKey().equalsIgnoreCase(requestedPackageKey)) {
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

    private void autoLoadBundle(BundleArchiveDescriptor descriptor) throws BundleException {
        BundlePreference preference = loadPreference(descriptor.bundleId(), descriptor.sourceZipName());
        if (preference.isBundleDisabled()) {
            clearBundleFailures(descriptor.bundleId());
            return;
        }

        VariantSelectionDecision selectionDecision = resolveVariantSelection(descriptor, preference, null, true);
        if (selectionDecision.preference() != preference) {
            savePreference(selectionDecision.preference());
            preference = selectionDecision.preference();
        }
        uninstallConflictingVariantPackages(descriptor, selectionDecision.targetPackages());
        installEnabledPackages(descriptor, selectionDecision.targetPackages(), preference, false);
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
                succeededPackages.add(installablePackage);
                continue;
            }

            try {
                List<BundleInstallResult> results = installBundle(descriptor.sourceZipName(), installablePackage);
                for (BundleInstallResult result : results) {
                    messages.addAll(result.getWarnings());
                }
                clearPackageFailure(descriptor.bundleId(), installablePackage);
                succeededPackages.add(installablePackage);
            } catch (BundleException ex) {
                recordBundleFailure(descriptor.bundleId(), installablePackage, ex.getMessage());
                failedPackages.add(installablePackage);
                messages.add(ex.getMessage());
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
            String packageKey = resolvePackageKey(descriptor.installablePackages(), requestedPackageKey);
            BundlePreference updatedPreference = rememberSelectedVariant(preference, descriptor, packageKey);
            return new VariantSelectionDecision(updatedPreference, List.of(packageKey), List.of());
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

        return List.of(resolvePackageKey(installablePackages, requestedPackageKey));
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

    private String basePluginKey(String packageKey) {
        int separator = packageKey.indexOf('@');
        return separator < 0 ? packageKey : packageKey.substring(0, separator);
    }

    private String resolvePackageKey(List<String> packageKeys, String requestedPackageKey) throws BundleException {
        for (String packageKey : packageKeys) {
            if (packageKey.equalsIgnoreCase(requestedPackageKey)) {
                return packageKey;
            }
        }
        throw new BundleException("Package not found in bundle: " + requestedPackageKey);
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
        for (BundleArchiveDescriptor descriptor : scanIncomingBundleDescriptors()) {
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

    private List<BundleArchiveDescriptor> scanIncomingBundleDescriptors() {
        File[] files = listIncomingBundleSources();
        if (files == null || files.length == 0) {
            return List.of();
        }

        ArrayList<BundleArchiveDescriptor> descriptors = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                String bundleId = computeBundleId(file);
                ArchivePackageInfo packageInfo = inspectArchivePackages(file);
                descriptors.add(new BundleArchiveDescriptor(
                        bundleId,
                        file.getName(),
                        packageInfo.installablePackages(),
                        packageInfo.allPackages(),
                        packageInfo.variantGroups(),
                        packageInfo.variantChoiceGroups()
                ));
            } catch (BundleException | IOException ex) {
                plugin.getLogger().warning("Failed to inspect bundle " + file.getName() + ": " + ex.getMessage());
            }
        }
        descriptors.sort(Comparator.comparing(BundleArchiveDescriptor::sourceZipName, String.CASE_INSENSITIVE_ORDER));
        return descriptors;
    }

    private ArchivePackageInfo inspectArchivePackages(File archiveFile) throws BundleException, IOException {
        try (BundleArchive archive = openArchive(archiveFile)) {
            TreeSet<String> installable = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            TreeSet<String> allPackages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<BundlePackageDescriptor> descriptors = discoverPackageDescriptors(archive);
            List<BundleVariantGroup> variantGroups = buildVariantGroups(descriptors);
            for (BundlePackageDescriptor packageDescriptor : descriptors) {
                allPackages.add(packageDescriptor.packageKey());
                if (packageDescriptor.supported()) {
                    installable.add(packageDescriptor.packageKey());
                }
            }
            return new ArchivePackageInfo(
                    new ArrayList<>(installable),
                    new ArrayList<>(allPackages),
                    variantGroups,
                    buildVariantChoiceGroups(variantGroups)
            );
        }
    }

    private List<BundlePackageDescriptor> discoverPackageDescriptors(BundleArchive archive) throws BundleException {
        ArrayList<BundleArchiveEntry> normalizedEntries = new ArrayList<>();
        for (BundleArchiveEntry entry : archive.entries()) {
            if (entry.isDirectory()) {
                continue;
            }
            normalizedEntries.add(new BundleArchiveEntry(PathUtils.normalizeZipPath(entry.getName()), false));
        }

        List<BundlePackageMatch> resourcePackMatches = discoverResourcePackMatches(normalizedEntries);
        LinkedHashMap<String, BundlePackageMatch> matches = new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<BundleArchiveEntry>> entriesByPackage = new LinkedHashMap<>();

        for (BundleArchiveEntry entry : normalizedEntries) {
            String normalizedEntry = entry.getName();
            BundlePackageMatch match = findResourcePackMatch(normalizedEntry, resourcePackMatches);
            if (match == null) {
                match = detectPackageMatch(normalizedEntry);
            }
            if (match == null) {
                continue;
            }

            String descriptorKey = descriptorKey(match);
            matches.putIfAbsent(descriptorKey, match);
            entriesByPackage.computeIfAbsent(descriptorKey, ignored -> new ArrayList<>())
                    .add(new BundleArchiveEntry(normalizedEntry, false));
        }

        LinkedHashMap<String, List<BundlePackageMatch>> supportedByPlugin = new LinkedHashMap<>();
        for (BundlePackageMatch match : matches.values()) {
            if (!match.supported()) {
                continue;
            }
            supportedByPlugin.computeIfAbsent(match.pluginKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(match);
        }

        ArrayList<BundlePackageDescriptor> descriptors = new ArrayList<>(matches.size());
        for (Map.Entry<String, BundlePackageMatch> entry : matches.entrySet()) {
            BundlePackageMatch match = entry.getValue();
            List<BundlePackageMatch> pluginMatches = supportedByPlugin.getOrDefault(match.pluginKey().toLowerCase(Locale.ROOT), List.of());
            String packageKey = match.supported() && pluginMatches.size() > 1
                    ? buildPackageKey(match.pluginKey(), match.normalizedVariantParts())
                    : match.pluginKey();
            descriptors.add(new BundlePackageDescriptor(
                    packageKey,
                    match.pluginKey(),
                    match.rootPath(),
                    match.supported(),
                    entriesByPackage.getOrDefault(entry.getKey(), new ArrayList<>()),
                    match.rawVariantParts(),
                    match.normalizedVariantParts()
            ));
        }
        descriptors.sort(Comparator.comparing(BundlePackageDescriptor::packageKey, String.CASE_INSENSITIVE_ORDER));
        return descriptors;
    }

    private List<BundlePackageMatch> discoverResourcePackMatches(List<BundleArchiveEntry> entries) throws BundleException {
        LinkedHashMap<String, BundlePackageMatch> matches = new LinkedHashMap<>();
        for (BundleArchiveEntry entry : entries) {
            List<String> segments = PathUtils.splitSegments(entry.getName());
            BundlePackageMatch match = detectResourcePackRoot(segments);
            if (match != null) {
                matches.putIfAbsent(match.rootPath().toLowerCase(Locale.ROOT), match);
            }
        }

        ArrayList<BundlePackageMatch> ordered = new ArrayList<>(matches.values());
        ordered.sort(Comparator.comparingInt((BundlePackageMatch match) -> match.rootPath().length()).reversed());
        return ordered;
    }

    private BundlePackageMatch findResourcePackMatch(
            String normalizedEntry,
            List<BundlePackageMatch> resourcePackMatches
    ) {
        String loweredEntry = normalizedEntry.toLowerCase(Locale.ROOT);
        for (BundlePackageMatch match : resourcePackMatches) {
            String loweredRoot = match.rootPath().toLowerCase(Locale.ROOT);
            if (loweredRoot.isEmpty() || loweredEntry.equals(loweredRoot) || loweredEntry.startsWith(loweredRoot + "/")) {
                return match;
            }
        }
        return null;
    }

    private BundlePackageMatch detectPackageMatch(String normalizedEntry) throws BundleException {
        List<String> segments = PathUtils.splitSegments(normalizedEntry);
        if (segments.size() < 2) {
            return null;
        }

        for (int index = 0; index < segments.size() - 1; index++) {
            PluginFolderInfo folderInfo = parsePluginFolderInfo(segments.get(index));
            if (folderInfo == null) {
                continue;
            }

            Optional<SupportedPluginInstaller> installer = installerRegistry.find(folderInfo.pluginName());
            if (installer.isEmpty()) {
                continue;
            }

            ArrayList<String> variantParts = new ArrayList<>();
            for (int variantIndex = 0; variantIndex < index; variantIndex++) {
                addVariantToken(variantParts, segments.get(variantIndex));
            }
            addVariantToken(variantParts, folderInfo.variantName());

            return new BundlePackageMatch(
                    installer.get().getPluginKey(),
                    String.join("/", segments.subList(0, index + 1)),
                    true,
                    rawVariantParts(segments, index, folderInfo.variantName()),
                    variantParts
            );
        }

        // Giu hanh vi cu cho unsupported top-level package, nhung khong xem file root la package.
        PluginFolderInfo topLevelInfo = parsePluginFolderInfo(segments.get(0));
        if (topLevelInfo == null) {
            return null;
        }

        return new BundlePackageMatch(
                topLevelInfo.pluginName(),
                segments.get(0),
                false,
                topLevelInfo.variantName() == null ? List.of() : List.of(topLevelInfo.variantName()),
                normalizeVariantParts(topLevelInfo.variantName() == null ? List.of() : List.of(topLevelInfo.variantName()))
        );
    }

    private BundlePackageMatch detectResourcePackRoot(List<String> segments) throws BundleException {
        String fileName = segments.get(segments.size() - 1);
        if (!"pack.mcmeta".equalsIgnoreCase(fileName)) {
            return null;
        }

        int resourcePackRootIndex = segments.size() - 2;
        if (resourcePackRootIndex < 0) {
            return new BundlePackageMatch(
                    "ResourcePack",
                    "",
                    true,
                    List.of(),
                    List.of()
            );
        }

        PluginFolderInfo folderInfo = parsePluginFolderInfo(segments.get(resourcePackRootIndex));
        ArrayList<String> variantParts = new ArrayList<>();
        for (int variantIndex = 0; variantIndex < resourcePackRootIndex; variantIndex++) {
            addVariantToken(variantParts, segments.get(variantIndex));
        }
        addVariantToken(variantParts, folderInfo.variantName());
        addVariantToken(variantParts, folderInfo.pluginName());

        return new BundlePackageMatch(
                "ResourcePack",
                String.join("/", segments.subList(0, resourcePackRootIndex + 1)),
                true,
                rawResourcePackVariantParts(segments, resourcePackRootIndex, folderInfo),
                variantParts
        );
    }

    private List<String> rawResourcePackVariantParts(
            List<String> segments,
            int resourcePackRootIndex,
            PluginFolderInfo folderInfo
    ) {
        ArrayList<String> parts = new ArrayList<>();
        for (int variantIndex = 0; variantIndex < resourcePackRootIndex; variantIndex++) {
            if (!segments.get(variantIndex).isBlank()) {
                parts.add(segments.get(variantIndex).trim());
            }
        }
        if (!folderInfo.pluginName().isBlank()) {
            parts.add(folderInfo.pluginName().trim());
        }
        if (folderInfo.variantName() != null && !folderInfo.variantName().isBlank()) {
            parts.add(folderInfo.variantName().trim());
        }
        return parts;
    }

    private List<String> rawVariantParts(List<String> segments, int pluginIndex, String suffixVariant) {
        ArrayList<String> parts = new ArrayList<>();
        for (int variantIndex = 0; variantIndex < pluginIndex; variantIndex++) {
            if (!segments.get(variantIndex).isBlank()) {
                parts.add(segments.get(variantIndex).trim());
            }
        }
        if (suffixVariant != null && !suffixVariant.isBlank()) {
            parts.add(suffixVariant.trim());
        }
        return parts;
    }

    private List<String> normalizeVariantParts(List<String> rawParts) {
        ArrayList<String> normalized = new ArrayList<>();
        for (String rawPart : rawParts) {
            addVariantToken(normalized, rawPart);
        }
        return normalized;
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
            for (int i = 0; i < pluginDescriptors.size(); i++) {
                BundlePackageDescriptor descriptor = pluginDescriptors.get(i);
                List<String> parts = reducedParts.get(i);
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

        for (int i = 0; i < reduced.size(); i++) {
            if (reduced.get(i).isEmpty()) {
                reduced.set(i, new ArrayList<>(rawParts.get(i)));
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

    private void addVariantToken(List<String> variants, String rawVariant) {
        if (rawVariant == null || rawVariant.isBlank()) {
            return;
        }

        String normalized = rawVariant.trim().replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
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

    private String descriptorKey(BundlePackageMatch match) {
        return match.pluginKey().toLowerCase(Locale.ROOT) + "|" + match.rootPath().toLowerCase(Locale.ROOT);
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
        File file = getPreferenceFile(bundleId);
        if (!file.exists()) {
            return new BundlePreference(bundleId, sourceZipName, false, List.of(), null, Map.of());
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
                configuration.getBoolean("bundleDisabled", false),
                configuration.getStringList("disabledPackages"),
                configuration.getString("selectedBundleVariant"),
                selectedPackages
        );
    }

    private List<BundlePreference> loadAllPreferences() {
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
                    configuration.getBoolean("bundleDisabled", false),
                    configuration.getStringList("disabledPackages"),
                    configuration.getString("selectedBundleVariant"),
                    readSelectedPackages(configuration)
            ));
        }
        return preferences;
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

    private void savePreference(BundlePreference preference) throws BundleException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bundleId", preference.getBundleId());
        configuration.set("sourceZipName", preference.getSourceZipName());
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

    private File getPreferenceFile(String bundleId) {
        return new File(preferenceDirectory, bundleId + ".yml");
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

    private boolean matchesBundleQuery(BundleRecord record, String normalizedQuery) {
        String sourceZipName = record.getSourceZipName().toLowerCase(Locale.ROOT);
        String sourceBaseName = baseName(record.getSourceZipName()).toLowerCase(Locale.ROOT);
        return record.getBundleId().toLowerCase(Locale.ROOT).startsWith(normalizedQuery)
                || record.getBundleShortId().toLowerCase(Locale.ROOT).startsWith(normalizedQuery)
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

    private void saveRecord(BundleRecord record) throws BundleException {
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
            throw new BundleException("Failed to save package record " + record.getId() + ".", ex);
        }
    }

    private BundleRecord loadRecord(File file) throws BundleException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String id = requireString(config, "id", file);
        String bundleId = requireString(config, "bundleId", file);
        String packageKey = requireString(config, "packageKey", file);
        String sourceZipName = requireString(config, "sourceZipName", file);
        long installedAt = config.getLong("installedAtEpochMillis");

        List<String> warnings = config.getStringList("warnings");
        List<String> createdDirectories = config.getStringList("createdDirectories");
        List<BundleRecord.InstalledFile> installedFiles = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("installedFiles")) {
            installedFiles.add(new BundleRecord.InstalledFile(
                    stringValue(raw.get("sourceEntry")),
                    stringValue(raw.get("pluginKey")),
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

    private List<BundleRecord.ConfigMutation> applyConfigMutations(
            List<BundleRecord.ConfigMutation> mutations,
            List<String> warnings
    ) throws BundleException {
        ArrayList<BundleRecord.ConfigMutation> applied = new ArrayList<>();
        for (BundleRecord.ConfigMutation mutation : mutations) {
            // Chi luu mutation thuc su vua duoc them de tranh go nham config co san.
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

    private void safeRollbackConfigMutations(List<BundleRecord.ConfigMutation> mutations) {
        try {
            rollbackConfigMutations(mutations);
        } catch (BundleException ex) {
            plugin.getLogger().warning("Failed to rollback config mutation: " + ex.getMessage());
        }
    }

    private void rollbackInstall(
            List<BundleRecord.InstalledFile> installedFiles,
            Collection<String> createdDirectories
    ) {
        List<BundleRecord.InstalledFile> reversedFiles = new ArrayList<>(installedFiles);
        reversedFiles.sort(Comparator.comparingInt((BundleRecord.InstalledFile file) -> file.getTargetPath().length()).reversed());
        for (BundleRecord.InstalledFile installedFile : reversedFiles) {
            deleteIfExists(resolveServerPath(installedFile.getTargetPath()));
        }

        restoreBackups(installedFiles);
        deleteCreatedDirectories(createdDirectories);
    }

    private void restoreBackups(List<BundleRecord.InstalledFile> installedFiles) {
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
                throw new UncheckedIOException(ex);
            }
        }
    }

    private void deleteCreatedDirectories(Collection<String> createdDirectories) {
        List<String> ordered = new ArrayList<>(createdDirectories);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        for (String relativeDirectory : ordered) {
            Path directoryPath = resolveServerPath(relativeDirectory);
            try {
                Files.deleteIfExists(directoryPath);
            } catch (DirectoryNotEmptyException ignored) {
                // Thu muc nay da duoc dung boi file khac thi de nguyen.
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to delete directory " + directoryPath + ": " + ex.getMessage());
            }
        }
    }

    private void cleanupTransientArtifacts(String recordId) {
        // Ban ghi duoc luu rieng, backup chi la du lieu tam de rollback/uninstall.
        deleteIfExists(getRecordFile(recordId).toPath());
        deleteRecursively(backupDirectory.toPath().resolve(recordId));
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

    private File getRecordFile(String recordId) {
        return new File(packageDirectory, recordId + ".yml");
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

    private String computeBundleId(File source) throws BundleException {
        return allocateBundleId(source.getName());
    }

    private String allocateBundleId(String sourceName) throws BundleException {
        YamlConfiguration index = YamlConfiguration.loadConfiguration(bundleIndexFile);
        String sourceKey = normalizedSourceKey(sourceName);

        String existingId = index.getString("entries." + sourceKey);
        if (existingId != null && !existingId.isBlank()) {
            return existingId;
        }

        String migratedId = findExistingBundleIdForSource(sourceName);
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

    private String findExistingBundleIdForSource(String sourceName) {
        for (BundleRecord record : listInstalledPackageRecords()) {
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

    private String shortId(String value) {
        return value;
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

    private static final class BundleVariantAggregate {
        private final String displayName;
        private final LinkedHashMap<String, String> selections;

        private BundleVariantAggregate(String displayName) {
            this.displayName = displayName;
            this.selections = new LinkedHashMap<>();
        }
    }

    private record ConfigMutationResult(boolean applied, String warning) {
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

    private record BundleArchiveDescriptor(
            String bundleId,
            String sourceZipName,
            List<String> installablePackages,
            List<String> allPackages,
            List<BundleVariantGroup> variantGroups,
            List<VariantChoiceGroup> variantChoiceGroups
    ) {
    }

    private record ArchivePackageInfo(
            List<String> installablePackages,
            List<String> allPackages,
            List<BundleVariantGroup> variantGroups,
            List<VariantChoiceGroup> variantChoiceGroups
    ) {
    }

    private record BundlePackageDescriptor(
            String packageKey,
            String pluginKey,
            String rootPath,
            boolean supported,
            List<BundleArchiveEntry> entries,
            List<String> rawVariantParts,
            List<String> normalizedVariantParts
    ) {
    }

    private record BundlePackageMatch(
            String pluginKey,
            String rootPath,
            boolean supported,
            List<String> rawVariantParts,
            List<String> normalizedVariantParts
    ) {
    }

    private record PluginFolderInfo(
            String pluginName,
            String variantName
    ) {
    }

    private record BundleVariantGroup(
            String pluginKey,
            List<BundleVariantOption> options
    ) {
    }

    private record BundleVariantOption(
            String packageKey,
            String displayName
    ) {
    }

    private record VariantPromptOption(
            int index,
            String bundleId,
            String sourceZipName,
            String title,
            String displayName
            ,
            Map<String, String> selections
    ) {
    }

    private record VariantChoiceGroup(
            String title,
            List<VariantChoiceOption> options
    ) {
    }

    private record VariantChoiceOption(
            String displayName,
            Map<String, String> selections
    ) {
    }

    private record VariantSelectionDecision(
            BundlePreference preference,
            List<String> targetPackages,
            List<String> messages
    ) {
    }

    private record KnownBundle(
            String bundleId,
            String sourceZipName,
            BundleArchiveDescriptor archiveDescriptor,
            List<String> installablePackages,
            List<String> allKnownPackages
    ) {
    }
}
