package tk.funayd.bundleManager.installer;

import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.PathUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ReferenceRewriteContext {

    private final Map<String, String> relativePathMap = new LinkedHashMap<>();
    private final Map<String, String> fileNameMap = new LinkedHashMap<>();
    private final Map<String, String> baseNameMap = new LinkedHashMap<>();

    public ReferenceRewriteContext(List<ResolvedBundleFile> plannedFiles) throws BundleException {
        Set<String> ambiguousFileNames = new HashSet<>();
        Set<String> ambiguousBaseNames = new HashSet<>();

        for (ResolvedBundleFile plannedFile : plannedFiles) {
            String sourceRelativePath = PathUtils.normalizeRelativePath(plannedFile.getSourceRelativePath());
            String targetRelativePath = PathUtils.normalizeRelativePath(plannedFile.getTargetRelativePath());
            relativePathMap.put(sourceRelativePath.toLowerCase(Locale.ROOT), targetRelativePath);

            String sourceFileName = fileName(sourceRelativePath);
            String targetFileName = fileName(targetRelativePath);
            putUnique(fileNameMap, ambiguousFileNames, sourceFileName, targetFileName);
            putUnique(baseNameMap, ambiguousBaseNames, PathUtils.baseName(sourceFileName), PathUtils.baseName(targetFileName));
        }
    }

    public Optional<String> renameRelativePath(String sourceRelativePath) {
        if (sourceRelativePath == null || sourceRelativePath.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(relativePathMap.get(sourceRelativePath.toLowerCase(Locale.ROOT)));
    }

    public Optional<String> renameFileName(String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fileNameMap.get(sourceFileName.toLowerCase(Locale.ROOT)));
    }

    public Optional<String> renameBaseName(String sourceBaseName) {
        if (sourceBaseName == null || sourceBaseName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(baseNameMap.get(sourceBaseName.toLowerCase(Locale.ROOT)));
    }

    public String renameFileNameOrOriginal(String sourceFileName) {
        return renameFileName(sourceFileName).orElse(sourceFileName);
    }

    public String renameBaseNameOrOriginal(String sourceBaseName) {
        return renameBaseName(sourceBaseName).orElse(sourceBaseName);
    }

    private void putUnique(
            Map<String, String> mapping,
            Set<String> ambiguousKeys,
            String sourceKey,
            String targetValue
    ) {
        String normalizedKey = sourceKey.toLowerCase(Locale.ROOT);
        if (ambiguousKeys.contains(normalizedKey)) {
            return;
        }

        String existingValue = mapping.putIfAbsent(normalizedKey, targetValue);
        if (existingValue != null && !existingValue.equals(targetValue)) {
            mapping.remove(normalizedKey);
            ambiguousKeys.add(normalizedKey);
        }
    }

    private String fileName(String relativePath) throws BundleException {
        List<String> segments = PathUtils.splitSegments(relativePath);
        return segments.get(segments.size() - 1);
    }
}
