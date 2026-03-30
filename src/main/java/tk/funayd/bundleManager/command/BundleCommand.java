package tk.funayd.bundleManager.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import tk.funayd.bundleManager.bundle.BundleActionReport;
import tk.funayd.bundleManager.bundle.BundleException;
import tk.funayd.bundleManager.bundle.BundleLoadReport;
import tk.funayd.bundleManager.bundle.BundleOverallState;
import tk.funayd.bundleManager.bundle.BundlePackageState;
import tk.funayd.bundleManager.bundle.BundlePackageView;
import tk.funayd.bundleManager.bundle.BundleService;
import tk.funayd.bundleManager.bundle.BundleStatusView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BundleCommand implements TabExecutor {

    private final BundleService bundleService;

    public BundleCommand(BundleService bundleService) {
        this.bundleService = bundleService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bundlemanager.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use BundleManager.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "enable" -> enableBundle(sender, label, args);
                case "disable" -> disableBundle(sender, label, args);
                case "variant" -> showVariants(sender, label, args);
                case "chose" -> switchVariant(sender, label, args);
                case "reload" -> reloadBundles(sender);
                case "list" -> listBundles(sender);
                case "supported" -> listSupportedPlugins(sender);
                default -> sendHelp(sender, label);
            }
        } catch (BundleException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bundlemanager.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterByPrefix(List.of("enable", "disable", "variant", "chose", "reload", "list", "supported"), args[0]);
        }

        if ("enable".equalsIgnoreCase(args[0]) || "disable".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return filterByPrefix(bundleService.listKnownBundleIds(), args[1]);
            }
            if (args.length == 3) {
                return filterByPrefix(bundleService.listKnownPackageKeys(args[1]), args[2]);
            }
        }

        if ("variant".equalsIgnoreCase(args[0]) && args.length == 2) {
            return filterByPrefix(bundleService.listKnownBundleIds(), args[1]);
        }

        if ("chose".equalsIgnoreCase(args[0]) && args.length == 2) {
            return filterByPrefix(bundleService.listPendingVariantIndexes(), args[1]);
        }

        return List.of();
    }

    private void enableBundle(CommandSender sender, String label, String[] args) throws BundleException {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " enable <bundleId> [package]");
            return;
        }

        BundleActionReport report = bundleService.enableBundleById(args[1], args.length >= 3 ? args[2] : null);
        sender.sendMessage(ChatColor.GREEN + "Enable requested for bundle "
                + ChatColor.AQUA + report.getBundleShortId()
                + ChatColor.GRAY + " (" + report.getSourceZipName() + ")");
        if (!report.getSucceededPackages().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Enabled packages: "
                    + ChatColor.WHITE + String.join(", ", report.getSucceededPackages()));
        }
        if (!report.getDisabledPackages().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Still disabled: "
                    + ChatColor.WHITE + String.join(", ", report.getDisabledPackages()));
        }
        if (!report.getFailedPackages().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Failed packages: "
                    + ChatColor.WHITE + String.join(", ", report.getFailedPackages()));
        }
        for (String message : report.getMessages()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private void disableBundle(CommandSender sender, String label, String[] args) throws BundleException {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " disable <bundleId> [package]");
            return;
        }

        BundleActionReport report = bundleService.disableBundleById(args[1], args.length >= 3 ? args[2] : null);
        sender.sendMessage(ChatColor.GREEN + "Disable requested for bundle "
                + ChatColor.AQUA + report.getBundleShortId()
                + ChatColor.GRAY + " (" + report.getSourceZipName() + ")");
        if (!report.getDisabledPackages().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Disabled packages: "
                    + ChatColor.WHITE + String.join(", ", report.getDisabledPackages()));
        }
        if (!report.getSucceededPackages().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Affected packages: "
                    + ChatColor.WHITE + String.join(", ", report.getSucceededPackages()));
        }
    }

    private void showVariants(CommandSender sender, String label, String[] args) throws BundleException {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " variant <bundleId>");
            return;
        }

        for (String message : bundleService.openVariantPrompt(args[1])) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private void listBundles(CommandSender sender) {
        List<BundleStatusView> bundles = bundleService.listBundleStatusViews();
        if (bundles.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No bundles were found.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Bundles:");
        for (BundleStatusView bundle : bundles) {
            sender.sendMessage(formatBundleStatus(bundle));
        }
    }

    private void reloadBundles(CommandSender sender) {
        BundleLoadReport report = bundleService.autoLoadBundles();
        sender.sendMessage(ChatColor.GREEN + "Installed "
                + ChatColor.AQUA + report.getInstalledPackageCount()
                + ChatColor.GREEN + " package from "
                + ChatColor.AQUA + report.getInstalledBundleCount()
                + ChatColor.GREEN + " bundle.");
        sender.sendMessage(ChatColor.GRAY + "Disabled bundles/packages were skipped.");
        if (bundleService.hasIgnoredIncomingFiles()) {
            sender.sendMessage(ChatColor.YELLOW + "Ignored non-zip files in bundles folder. Only .zip bundles are loaded.");
        }
        for (String warning : report.getWarnings()) {
            sender.sendMessage(ChatColor.YELLOW + warning);
        }
        for (String error : report.getErrors()) {
            sender.sendMessage(ChatColor.RED + error);
        }
    }

    private void switchVariant(CommandSender sender, String label, String[] args) throws BundleException {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " chose <index>");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            throw new BundleException("Variant index must be a number: " + args[1]);
        }

        BundleActionReport report = bundleService.switchVariant(index);
        sender.sendMessage(ChatColor.GREEN + "Switched variant for bundle "
                + ChatColor.AQUA + report.getBundleShortId()
                + ChatColor.GRAY + " (" + report.getSourceZipName() + ")");
        if (!report.getSucceededPackages().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Enabled packages: "
                    + ChatColor.WHITE + String.join(", ", report.getSucceededPackages()));
        }
        if (!report.getFailedPackages().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Failed packages: "
                    + ChatColor.WHITE + String.join(", ", report.getFailedPackages()));
        }
        for (String message : report.getMessages()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private String formatBundleStatus(BundleStatusView bundle) {
        ChatColor bundleColor = switch (bundle.getOverallState()) {
            case SUCCESS -> ChatColor.GREEN;
            case FAILED -> ChatColor.RED;
            case PARTIAL -> ChatColor.YELLOW;
            case DISABLED -> ChatColor.GRAY;
        };

        ArrayList<String> packages = new ArrayList<>(bundle.getPackageViews().size());
        for (BundlePackageView packageView : bundle.getPackageViews()) {
            ChatColor packageColor = switch (packageView.getState()) {
                case SUCCESS -> ChatColor.GREEN;
                case FAILED -> ChatColor.RED;
                case DISABLED -> ChatColor.GRAY;
                case UNSUPPORTED -> ChatColor.YELLOW;
            };
            packages.add(packageColor + packageView.getDisplayName());
        }

        return ChatColor.DARK_GRAY + "- "
                + ChatColor.AQUA + bundle.getBundleShortId()
                + ChatColor.DARK_GRAY + " | "
                + bundleColor + bundle.getBundleName()
                + ChatColor.WHITE + " ("
                + String.join(ChatColor.WHITE + ", ", packages)
                + ChatColor.WHITE + ")";
    }

    private void listSupportedPlugins(CommandSender sender) {
        List<String> supportedPlugins = bundleService.listSupportedPlugins();
        if (supportedPlugins.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No supported plugin installers were found.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Supported plugin installers:");
        for (String pluginKey : supportedPlugins) {
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + pluginKey);
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "BundleManager commands:");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " enable <bundleId> [package]");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " disable <bundleId> [package]");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " variant <bundleId>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " chose <index>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " reload");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " list");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " supported");
    }

    private List<String> filterByPrefix(List<String> values, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return values;
        }

        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        ArrayList<String> results = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                results.add(value);
            }
        }
        return results;
    }
}
