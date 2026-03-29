package tk.funayd.bundleManager.bundle;

public final class BundleException extends Exception {

    public BundleException(String message) {
        super(message);
    }

    public BundleException(String message, Throwable cause) {
        super(message, cause);
    }
}
