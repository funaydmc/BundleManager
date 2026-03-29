package tk.funayd.bundleManager.bundle;

public final class BundleArchiveEntry {

    private final String name;
    private final boolean directory;

    public BundleArchiveEntry(String name, boolean directory) {
        this.name = name;
        this.directory = directory;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }
}
