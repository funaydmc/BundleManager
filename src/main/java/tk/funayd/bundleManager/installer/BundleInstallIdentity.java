package tk.funayd.bundleManager.installer;

import java.util.Locale;

public final class BundleInstallIdentity {

    private final String type;
    private final String id;
    private final String source;

    public BundleInstallIdentity(String type, String id, String source) {
        this.type = type;
        this.id = id;
        this.source = source;
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String uniqueKey() {
        return type.toLowerCase(Locale.ROOT) + "|" + id.toLowerCase(Locale.ROOT);
    }
}
