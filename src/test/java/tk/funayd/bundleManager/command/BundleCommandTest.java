package tk.funayd.bundleManager.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tk.funayd.bundleManager.bundle.BundleActionReport;
import tk.funayd.bundleManager.bundle.BundleOverallState;
import tk.funayd.bundleManager.bundle.BundlePackageState;
import tk.funayd.bundleManager.bundle.BundlePackageView;
import tk.funayd.bundleManager.bundle.BundleService;
import tk.funayd.bundleManager.bundle.BundleStatusView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BundleCommandTest {

    @Test
    void shouldTabCompleteEnableDisableArguments() {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.listKnownBundleIds()).thenReturn(List.of("347272", "12ab34"));
        when(bundleService.listKnownPackageKeys("347272")).thenReturn(List.of("ItemsAdder", "MMOItems", "MythicMobs@vanilla"));
        when(bundleService.listPendingVariantIndexes()).thenReturn(List.of("1", "2"));

        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        List<String> subCommands = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"e"});
        List<String> reloadCommands = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"re"});
        List<String> bundleIds = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"enable", "34"});
        List<String> packages = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"disable", "347272", "MM"});
        List<String> variantPackages = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"enable", "347272", "Mythic"});
        List<String> variantBundleIds = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"variant", "34"});
        List<String> variantIndexes = command.onTabComplete(sender, mock(Command.class), "bm", new String[]{"chose", "2"});

        assertEquals(List.of("enable"), subCommands);
        assertEquals(List.of("reload"), reloadCommands);
        assertEquals(List.of("347272"), bundleIds);
        assertEquals(List.of("MMOItems"), packages);
        assertEquals(List.of("MythicMobs@vanilla"), variantPackages);
        assertEquals(List.of("347272"), variantBundleIds);
        assertEquals(List.of("2"), variantIndexes);
    }

    @Test
    void shouldPassBundleIdAndPackageToEnableService() throws Exception {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.enableBundleById("347272", "MythicMobs")).thenReturn(new BundleActionReport(
                "347272abcdef",
                "bundle.zip",
                List.of("MythicMobs"),
                List.of(),
                List.of(),
                List.of()
        ));

        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        command.onCommand(sender, mock(Command.class), "bm", new String[]{"enable", "347272", "MythicMobs"});

        verify(bundleService).enableBundleById("347272", "MythicMobs");
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Enable requested for bundle")));
    }

    @Test
    void shouldRenderBundleCentricList() {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.listBundleStatusViews()).thenReturn(List.of(
                new BundleStatusView(
                        "347272abcdef",
                        "LostAssets.zip",
                        List.of(
                                new BundlePackageView("ItemsAdder", "ItemsAdder", BundlePackageState.SUCCESS),
                                new BundlePackageView("MythicMobs@model", "MythicMobs [2]", BundlePackageState.SUCCESS),
                                new BundlePackageView("DeluxeMenus@server_menu", "DeluxeMenus", BundlePackageState.FAILED)
                        ),
                        BundleOverallState.PARTIAL
                )
        ));

        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        command.onCommand(sender, mock(Command.class), "bm", new String[]{"list"});

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Bundles:")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("LostAssets")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("ItemsAdder")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("MythicMobs [2]")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("DeluxeMenus")));
        assertTrue(messageCaptor.getAllValues().stream().noneMatch(message -> message.contains("UnknownPlugin")));
        assertTrue(messageCaptor.getAllValues().stream().noneMatch(message -> message.contains("DeluxeMenus@server_menu")));
    }

    @Test
    void shouldReloadBundles() {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.hasIgnoredIncomingFiles()).thenReturn(true);
        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        command.onCommand(sender, mock(Command.class), "bm", new String[]{"reload"});

        verify(bundleService).autoLoadBundles();
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Reloaded bundles")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Ignored non-zip files")));
        assertTrue(messageCaptor.getAllValues().stream().noneMatch(message -> message.contains("Multiple variant detected")));
    }

    @Test
    void shouldShowVariantPromptByBundleId() throws Exception {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.openVariantPrompt("347272")).thenReturn(List.of(
                "&aMultiple variant detected. Use \"/bm chose <index>\" to swich.",
                "--- MythicMobs ---",
                "    1. vanilla"
        ));

        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        command.onCommand(sender, mock(Command.class), "bm", new String[]{"variant", "347272"});

        verify(bundleService).openVariantPrompt("347272");
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Multiple variant detected")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("--- MythicMobs ---")));
    }

    @Test
    void shouldSwitchVariantByIndex() throws Exception {
        BundleService bundleService = mock(BundleService.class);
        when(bundleService.switchVariant(2)).thenReturn(new BundleActionReport(
                "347272abcdef",
                "mega_bundle",
                List.of("MythicMobs@vanilla"),
                List.of(),
                List.of(),
                List.of("Selected variant: Bundle - vanilla")
        ));

        BundleCommand command = new BundleCommand(bundleService);
        CommandSender sender = allowedSender();

        command.onCommand(sender, mock(Command.class), "bm", new String[]{"chose", "2"});

        verify(bundleService).switchVariant(2);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Switched variant")));
        assertTrue(messageCaptor.getAllValues().stream().anyMatch(message -> message.contains("Selected variant")));
    }

    private CommandSender allowedSender() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(eq("bundlemanager.admin"))).thenReturn(true);
        return sender;
    }
}
