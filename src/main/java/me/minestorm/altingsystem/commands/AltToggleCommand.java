package me.minestorm.altingsystem.commands;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AltToggleCommand implements CommandExecutor {

    private final MineStormAltingSystemPlugin plugin;

    public AltToggleCommand(MineStormAltingSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("minestorm.togglealerts")) {
            player.sendMessage(ChatTheme.error("You do not have permission to use this command."));
            return true;
        }

        boolean nowDisabled = !plugin.hasAlertsDisabled(player.getUniqueId());
        plugin.setAlertsDisabled(player.getUniqueId(), nowDisabled);

        player.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Alt alerts "
                + (nowDisabled
                ? ChatColor.RED + "" + ChatColor.BOLD + "DISABLED"
                : ChatColor.GREEN + "" + ChatColor.BOLD + "ENABLED")
                + ChatColor.RESET + ChatColor.GRAY + ".");
        return true;
    }
}
