package tk.funayd.bundleManager.installer;

import tk.funayd.bundleManager.bundle.BundleException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class AbstractDirectoryInstaller extends AbstractPluginInstaller {

    @Override
    public Optional<ResolvedBundleFile> resolveFile(String relativePath, String bundleIdShort) throws BundleException {
        if (isIgnoredDataFile(relativePath)) {
            return Optional.empty();
        }

        List<String> sourceSegments = pathSegments(relativePath);
        if (sourceSegments.isEmpty()) {
            return Optional.empty();
        }

        if (sourceSegments.size() == 1 && !shouldInstallRootFile(sourceSegments.get(0))) {
            return Optional.empty();
        }

        if (sourceSegments.size() > 1 && !shouldInstallPath(sourceSegments)) {
            return Optional.empty();
        }

        List<String> targetSegments = transformTargetSegments(sourceSegments, bundleIdShort);
        return Optional.of(new ResolvedBundleFile(relativePath, pluginPath(String.join("/", targetSegments))));
    }

    protected boolean shouldInstallRootFile(String fileName) {
        return false;
    }

    protected boolean shouldInstallPath(List<String> sourceSegments) {
        return sourceSegments.size() >= 2;
    }

    protected boolean shouldPrefixLeafFile(List<String> sourceSegments) {
        return false;
    }

    protected String mapRootDirectoryName(String rootDirectory) {
        return rootDirectory;
    }

    protected List<String> transformTargetSegments(List<String> sourceSegments, String bundleIdShort) {
        ArrayList<String> targetSegments = new ArrayList<>(sourceSegments);
        targetSegments.set(0, mapRootDirectoryName(targetSegments.get(0)));
        if (shouldPrefixLeafFile(sourceSegments)) {
            int lastIndex = targetSegments.size() - 1;
            targetSegments.set(lastIndex, prefixFileName(targetSegments.get(lastIndex), bundleIdShort));
        }
        return targetSegments;
    }
}
