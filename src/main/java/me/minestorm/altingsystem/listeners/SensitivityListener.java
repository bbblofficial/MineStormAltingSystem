package me.minestorm.altingsystem.listeners;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class SensitivityListener implements Listener {

    private final MineStormAltingSystemPlugin plugin;
    private final SensitivityManager manager;

    public SensitivityListener(MineStormAltingSystemPlugin plugin, SensitivityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isTrackingEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) plugin.sendGrimProblem(player);
            }, 40L);
            return;
        }

        long delay = 20L * Math.max(1,
                plugin.getConfig().getInt("options.join-check-delay-seconds", 15));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !plugin.isTrackingEnabled()) return;
            String[] sens = plugin.getGrimSensitivity(player);
            if (sens == null) return;
            String hSens = sens[0], vSens = sens[1];
            if (!manager.isValid(hSens) || !manager.isValid(vSens)) return;

            List<String> alts = manager.findAlts(hSens, vSens, player.getName());
            if (!alts.isEmpty()) broadcastAlert(player.getName(), hSens, vSens, alts);

            manager.saveSensitivity(player.getUniqueId(), player.getName(), hSens, vSens);
        }, delay);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!plugin.isTrackingEnabled()) return;
        Player player = event.getPlayer();
        String[] sens = plugin.getGrimSensitivity(player);
        if (sens != null && manager.isValid(sens[0]) && manager.isValid(sens[1])) {
            manager.saveSensitivity(player.getUniqueId(), player.getName(), sens[0], sens[1]);
        }
    }

    private void broadcastAlert(String suspect, String hSens, String vSens, List<String> alts) {
        // Local staff first
        String[] lines = new String[]{
                ChatTheme.divider(),
                ChatTheme.PREFIX + ChatColor.RED + ChatColor.BOLD + "ALT ALERT",
                "  " + ChatColor.AQUA + "Player" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + suspect,
                "  " + ChatColor.AQUA + "Sensitivity" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE
                        + hSens + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + vSens,
                "  " + ChatColor.AQUA + "Matches" + ChatColor.DARK_GRAY + ": " + ChatTheme.formatAltNames(alts),
                ChatTheme.divider()
        };
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(MineStormAltingSystemPlugin.ALERT_PERMISSION)
                    && !plugin.hasAlertsDisabled(staff.getUniqueId())) {
                staff.sendMessage(lines);
            }
        }
        // Broadcast so staff on OTHER servers see it too
        if (plugin.getProxyBridge() != null) {
            plugin.getProxyBridge().broadcastAlert(suspect, hSens, vSens, alts);
        }
    }
}
