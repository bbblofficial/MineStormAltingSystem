package me.minestorm.altingsystem.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Central place for the plugin's chat look and feel:
 * dark gray dividers, aqua accents, red for suspected alts, green for clean checks.
 */
public final class ChatTheme {

    private static final String BAR = "\u25AC"; // ▬
    private static final int DIVIDER_LENGTH = 34;

    public static final String PREFIX =
            ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + ChatColor.BOLD + "MineStorm" + ChatColor.RESET
                    + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

    private ChatTheme() {
    }

    public static String divider() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DIVIDER_LENGTH; i++) {
            sb.append(BAR);
        }
        return ChatColor.DARK_GRAY + sb.toString();
    }

    /** Sends: divider + "  TITLE » subtitle". */
    public static void sendHeader(CommandSender sender, String title, String subtitle) {
        sender.sendMessage(divider());
        String line = "  " + ChatColor.AQUA + ChatColor.BOLD + title;
        if (subtitle != null && !subtitle.isEmpty()) {
            line += ChatColor.RESET + " " + ChatColor.DARK_GRAY + "\u00BB " + ChatColor.WHITE + subtitle;
        }
        sender.sendMessage(line);
        sender.sendMessage("");
    }

    public static void sendFooter(CommandSender sender) {
        sender.sendMessage(divider());
    }

    /** "  Label: value" line with an aqua label. */
    public static void sendField(CommandSender sender, String label, String value) {
        sender.sendMessage("  " + ChatColor.AQUA + label + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + value);
    }

    public static String tagClean() {
        return ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + ChatColor.BOLD + "\u2714 CLEAN"
                + ChatColor.RESET + ChatColor.DARK_GRAY + "]";
    }

    public static String tagAlt(int count) {
        return ChatColor.DARK_GRAY + "[" + ChatColor.RED + ChatColor.BOLD + "\u26A0 "
                + count + (count == 1 ? " SUSPECTED ALT" : " SUSPECTED ALTS")
                + ChatColor.RESET + ChatColor.DARK_GRAY + "]";
    }

    public static String tagOnline(boolean online) {
        return online
                ? ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "ONLINE" + ChatColor.DARK_GRAY + "]"
                : ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "OFFLINE" + ChatColor.DARK_GRAY + "]";
    }

    public static String error(String message) {
        return PREFIX + ChatColor.RED + message;
    }

    public static String info(String message) {
        return PREFIX + ChatColor.GRAY + message;
    }

    /**
     * Formats account names in red, marking those currently online.
     * Must be called from the main thread (queries online players).
     */
    public static String formatAltNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (i > 0) {
                sb.append(ChatColor.DARK_GRAY).append(", ");
            }
            sb.append(ChatColor.RED).append(name);
            if (Bukkit.getPlayerExact(name) != null) {
                sb.append(ChatColor.GRAY).append(" (online)");
            }
        }
        return sb.toString();
    }
}
