package tk.funayd.bundleManager.bundle;

import java.util.List;

public record MissingPluginRequirement(
        String pluginName,
        List<String> bundleIds
) {
}
