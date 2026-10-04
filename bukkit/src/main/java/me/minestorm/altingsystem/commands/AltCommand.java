package me.minestorm.altingsystem.commands;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AltCommand implements CommandExecutor, TabCompleter {

    private final MineStormAltingSystemPlugin plugin;
    private final SensitivityManager manager;

    public AltCommand(MineStormAltingSystemPlugin plugin, SensitivityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minestorm.alt")) {
            sender.sendMessage(ChatTheme.error("You do not have permission to use this command."));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Usage: "
                    + ChatColor.AQUA + "/alt <player>");
            return true;
        }

        final String requested = args[0];
        final Player localTarget = Bukkit.getPlayerExact(requested);
        final String displayName = localTarget != null ? localTarget.getName() : requested;
        final boolean localOnline = localTarget != null;

        // 1. Player is on this server -> read live from GrimAC.
        if (localOnline && plugin.isTrackingEnabled()) {
            String[] live = plugin.getGrimSensitivity(localTarget);
            if (live != null && manager.isValid(live[0]) && manager.isValid(live[1])) {
                List<String> alts = manager.findAlts(live[0], live[1], displayName);
                manager.saveSensitivity(localTarget.getUniqueId(), displayName, live[0], live[1]);
                printResult(sender, displayName, true, live[0], live[1], alts,
                        ChatColor.GREEN + "Live (GrimAC)");
                return true;
            }
        }

        // 2. Shared DB cache (works for players on other servers + offline).
        if (plugin.getConfig().getBoolean("options.allow-remote-lookup", true)) {
            String[] cached = manager.getOfflineSensitivity(displayName);
            if (cached != null) {
                List<String> alts = manager.findAlts(cached[0], cached[1], displayName);
                printResult(sender, displayName, localOnline, cached[0], cached[1], alts,
                        ChatColor.GRAY + "Shared DB");
                return true;
            }
        }

        // 3. Nothing locally -> ask the network.
        if (plugin.getProxyBridge() != null && plugin.getProxyBridge().isEnabled()) {
            sender.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Querying network for "
                    + ChatColor.AQUA + displayName + ChatColor.GRAY + "...");
            plugin.awaitRemoteAlt(displayName, (sens, alts) -> {
                if (sens == null) {
                    sender.sendMessage(ChatTheme.error("No data for " + displayName
                            + " on any server. He may never have been seen by GrimAC."));
                } else {
                    printResult(sender, displayName, localOnline, sens[0], sens[1],
                            alts == null ? new ArrayList<>() : alts,
                            ChatColor.GRAY + "Remote (network)");
                }
            });
            plugin.getProxyBridge().broadcastQuery(displayName);
            return true;
        }

        if (localOnline) {
            sender.sendMessage(ChatTheme.error(displayName + " has no valid sensitivity yet"
                    + (plugin.isTrackingEnabled()
                    ? " - GrimAC may still be calculating it."
                    : " and GrimAC tracking is currently disabled.")));
        } else {
            sender.sendMessage(ChatTheme.error(displayName
                    + " is offline and has no saved sensitivity data."));
        }
        return true;
    }

    private void printResult(CommandSender sender, String name, boolean online,
                             String h, String v, List<String> alts, String source) {
        ChatTheme.sendHeader(sender, "ALT CHECK", name + " " + ChatTheme.tagOnline(online));
        ChatTheme.sendField(sender, "Horizontal", h);
        ChatTheme.sendField(sender, "Vertical", v);
        ChatTheme.sendField(sender, "Source", source);
        sender.sendMessage("");
        if (alts.isEmpty()) {
            sender.sendMessage("  " + ChatTheme.tagClean() + ChatColor.GRAY
                    + " No matching accounts found.");
        } else {
            sender.sendMessage("  " + ChatTheme.tagAlt(alts.size()));
            sender.sendMessage("  " + ChatColor.AQUA + "Matches" + ChatColor.DARK_GRAY + ": "
                    + ChatTheme.formatAltNames(alts));
        }
        ChatTheme.sendFooter(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("minestorm.alt")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
