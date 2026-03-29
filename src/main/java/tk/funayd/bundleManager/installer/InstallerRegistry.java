package tk.funayd.bundleManager.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class InstallerRegistry {

    private final Map<String, SupportedPluginInstaller> installers;

    public InstallerRegistry(List<SupportedPluginInstaller> installers) {
        this.installers = new LinkedHashMap<>();
        for (SupportedPluginInstaller installer : installers) {
            this.installers.put(installer.getPluginKey().toLowerCase(Locale.ROOT), installer);
        }
    }

    public Optional<SupportedPluginInstaller> find(String pluginKey) {
        if (pluginKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(installers.get(pluginKey.toLowerCase(Locale.ROOT)));
    }

    public List<String> listSupportedPluginKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (SupportedPluginInstaller installer : installers.values()) {
            keys.add(installer.getPluginKey());
        }
        return keys;
    }
}
