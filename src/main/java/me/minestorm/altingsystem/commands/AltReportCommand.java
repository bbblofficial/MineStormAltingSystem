package me.minestorm.altingsystem.commands;

import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class AltReportCommand implements CommandExecutor {

    private final SensitivityManager manager;

    public AltReportCommand(SensitivityManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minestorm.altreport")) {
            sender.sendMessage(ChatTheme.error("You do not have permission to use this command."));
            return true;
        }

        List<Map.Entry<String, List<String>>> matches = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : manager.getSensitivityGroups().entrySet()) {
            if (entry.getValue().size() > 1) {
                matches.add(entry);
            }
        }
        // Largest groups first
        matches.sort(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size()).reversed());

        ChatTheme.sendHeader(sender, "ALT REPORT", matches.size() + (matches.size() == 1 ? " group" : " groups"));

        if (matches.isEmpty()) {
            sender.sendMessage("  " + ChatTheme.tagClean() + ChatColor.GRAY + " No matching sensitivities found.");
        } else {
            for (Map.Entry<String, List<String>> entry : matches) {
                List<String> names = entry.getValue();
                sender.sendMessage("  " + ChatColor.AQUA + "Sens " + ChatColor.DARK_GRAY + "["
                        + ChatColor.WHITE + entry.getKey() + ChatColor.DARK_GRAY + "] "
                        + ChatTheme.tagAlt(names.size()));
                sender.sendMessage("    " + ChatColor.DARK_GRAY + "\u2022 " + ChatTheme.formatAltNames(names));
            }
        }

        ChatTheme.sendFooter(sender);
        return true;
    }
}
