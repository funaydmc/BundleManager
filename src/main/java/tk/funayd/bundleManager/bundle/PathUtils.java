package tk.funayd.bundleManager.bundle;

import java.util.ArrayList;
import java.util.List;

public final class PathUtils {

    private PathUtils() {
    }

    public static String normalizeZipPath(String rawPath) throws BundleException {
        return normalizeRelativePath(rawPath.replace('\\', '/'));
    }

    public static String normalizeRelativePath(String rawPath) throws BundleException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new BundleException("Path cannot be empty.");
        }

        String normalized = rawPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        List<String> segments = splitSegments(normalized);
        return String.join("/", segments);
    }

    public static List<String> splitSegments(String rawPath) throws BundleException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new BundleException("Path cannot be empty.");
        }

        String[] rawSegments = rawPath.replace('\\', '/').split("/");
        ArrayList<String> segments = new ArrayList<>(rawSegments.length);

        for (String segment : rawSegments) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BundleException("Path cannot contain '..': " + rawPath);
            }
            segments.add(segment);
        }

        if (segments.isEmpty()) {
            throw new BundleException("Path cannot be empty: " + rawPath);
        }

        return segments;
    }

    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot);
    }

    public static String extensionWithoutDot(String fileName) {
        String extension = extension(fileName);
        return extension.isEmpty() ? "" : extension.substring(1);
    }
}
