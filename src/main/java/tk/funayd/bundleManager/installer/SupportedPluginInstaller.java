package tk.funayd.bundleManager.installer;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface SupportedPluginInstaller {

    String getPluginKey();

    // Installer chi duoc map file bundle sang file moi se duoc tao them.
    Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException;

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

    // Mutation chi duoc phep theo huong cong them va phai rollback duoc.
    List<BundleRecord.ConfigMutation> buildConfigMutations(List<ResolvedBundleFile> installedFiles, String bundleIdShort);
}
