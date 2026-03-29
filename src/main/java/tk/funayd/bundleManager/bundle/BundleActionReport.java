package tk.funayd.bundleManager.bundle;

import java.util.Collections;
import java.util.List;

public final class BundleActionReport {

    private final String bundleId;
    private final String sourceZipName;
    private final List<String> succeededPackages;
    private final List<String> failedPackages;
    private final List<String> disabledPackages;
    private final List<String> messages;

    public BundleActionReport(
            String bundleId,
            String sourceZipName,
            List<String> succeededPackages,
            List<String> failedPackages,
            List<String> disabledPackages,
            List<String> messages
    ) {
        this.bundleId = bundleId;
        this.sourceZipName = sourceZipName;
        this.succeededPackages = List.copyOf(succeededPackages);
        this.failedPackages = List.copyOf(failedPackages);
        this.disabledPackages = List.copyOf(disabledPackages);
        this.messages = List.copyOf(messages);
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getSourceZipName() {
        return sourceZipName;
    }

    public List<String> getSucceededPackages() {
        return Collections.unmodifiableList(succeededPackages);
    }

    public List<String> getFailedPackages() {
        return Collections.unmodifiableList(failedPackages);
    }

    public List<String> getDisabledPackages() {
        return Collections.unmodifiableList(disabledPackages);
    }

    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public String getBundleShortId() {
        return bundleId;
    }
}
