package tk.funayd.bundleManager.installer;

public final class ResolvedBundleFile {

    private final String sourceEntryName;
    private final String sourceRelativePath;
    private final String targetRelativePath;

    public ResolvedBundleFile(String sourceRelativePath, String targetRelativePath) {
        this(sourceRelativePath, sourceRelativePath, targetRelativePath);
    }

    public ResolvedBundleFile(String sourceEntryName, String sourceRelativePath, String targetRelativePath) {
        this.sourceEntryName = sourceEntryName;
        this.sourceRelativePath = sourceRelativePath;
        this.targetRelativePath = targetRelativePath;
    }

    public String getSourceEntryName() {
        return sourceEntryName;
    }

    public String getSourceRelativePath() {
        return sourceRelativePath;
    }

    public String getTargetRelativePath() {
        return targetRelativePath;
    }

    public ResolvedBundleFile withTargetRelativePath(String updatedTargetRelativePath) {
        return new ResolvedBundleFile(sourceEntryName, sourceRelativePath, updatedTargetRelativePath);
    }
}
