package tk.funayd.bundleManager;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import tk.funayd.bundleManager.bundle.BundleLoadReport;
import tk.funayd.bundleManager.bundle.BundleService;
import tk.funayd.bundleManager.command.BundleCommand;

public final class BundleManager extends JavaPlugin {

    private BundleService bundleService;

    @Override
    public void onEnable() {
        bundleService = new BundleService(this);
        bundleService.initialize();

        BundleCommand bundleCommand = new BundleCommand(bundleService);
        PluginCommand command = getCommand("bundle");
        if (command == null) {
            throw new IllegalStateException("Command 'bundle' is not defined in plugin.yml");
        }

        command.setExecutor(bundleCommand);
        command.setTabCompleter(bundleCommand);

        BundleLoadReport report = bundleService.autoLoadBundles();
        getLogger().info("Installed " + report.getInstalledPackageCount()
                + " package from " + report.getInstalledBundleCount() + " bundle.");
        if (bundleService.hasIgnoredIncomingFiles()) {
            getLogger().warning("Ignored non-zip files in bundles folder. Only .zip bundles are loaded.");
        }
        for (String warning : report.getWarnings()) {
            getLogger().warning(warning);
        }
        for (String error : report.getErrors()) {
            getLogger().warning(error);
        }

        getLogger().info("BundleManager is ready.");
        getLogger().info("Drop bundle folders or zip files into: " + bundleService.getIncomingBundleDirectory().getAbsolutePath());
        getLogger().info("Persistent bundle data: " + bundleService.getPersistentDataDirectory().getAbsolutePath());
        getLogger().info("Supported installers: " + String.join(", ", bundleService.listSupportedPlugins()));
    }

    @Override
    public void onDisable() {
        // No shutdown work is required for this plugin.
    }
}
