package tk.funayd.bundleManager.installer;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface SupportedPluginInstaller {

    String getPluginKey();

    // Installers may only map bundle files to newly created target files.
    Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException;

    // When true, overwrite is not applied immediately and is queued as a user-resolved conflict instead.
    default boolean canRequestOverwrite(ResolvedBundleFile bundleFile) {
        return false;
    }

    // Renaming is only allowed when the target already exists and the installer confirms it is safe.
    default Optional<ResolvedBundleFile> resolveRenameOnConflict(
            ResolvedBundleFile bundleFile,
            String bundleIdShort
    ) throws BundleException {
        return Optional.empty();
    }

    default boolean shouldRewriteFileContent(ResolvedBundleFile bundleFile) {
        return false;
    }

    default byte[] rewriteFileContent(
            ResolvedBundleFile bundleFile,
            byte[] originalContent,
            ReferenceRewriteContext rewriteContext
    ) throws BundleException {
        return originalContent;
    }

    default List<BundleInstallIdentity> collectIncomingIdentities(
            List<ResolvedBundleFile> plannedFiles,
            BundleFileReader fileReader
    ) throws BundleException {
        return List.of();
    }

    default List<BundleInstallIdentity> collectExistingIdentities(Path serverRoot) throws BundleException {
        return List.of();
    }

    // Mutations must stay additive and must be reversible.
    List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort);
}
