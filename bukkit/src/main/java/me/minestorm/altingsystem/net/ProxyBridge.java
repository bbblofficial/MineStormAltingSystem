package me.minestorm.altingsystem.net;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cross-server link through the Bungee / Velocity relay.
 * Sends QUERY / ALERT, receives QUERY_RESP from whichever server hosts the target.
 */
public final class ProxyBridge implements PluginMessageListener {

    private final MineStormAltingSystemPlugin plugin;
    private boolean enabled;
    private String secret;

    public ProxyBridge(MineStormAltingSystemPlugin plugin) { this.plugin = plugin; }

    public void enable() {
        if (!plugin.getConfig().getBoolean("network.proxy", true)) return;
        secret = plugin.getConfig().getString("network.secret", "change-me");
        try {
            Messenger m = plugin.getServer().getMessenger();
            m.registerOutgoingPluginChannel(plugin, Net.CHANNEL);
            m.registerIncomingPluginChannel(plugin, Net.CHANNEL, this);
            enabled = true;
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Plugin messaging unavailable: " + t.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }

    public void disable() {
        if (!enabled) return;
        Messenger m = plugin.getServer().getMessenger();
        try { m.unregisterOutgoingPluginChannel(plugin, Net.CHANNEL); } catch (Throwable ignored) {}
        try { m.unregisterIncomingPluginChannel(plugin, Net.CHANNEL, this); } catch (Throwable ignored) {}
        enabled = false;
    }

    public void broadcastQuery(String targetName) {
        if (enabled) send(Net.encode(secret, Net.QUERY, targetName));
    }

    public void broadcastAlert(String suspect, String h, String v, List<String> alts) {
        if (enabled) send(Net.encode(secret, Net.ALERT, suspect, h, v, String.join(",", alts)));
    }

    public void sendQueryResponse(String targetName, String h, String v, List<String> alts) {
        if (enabled) send(Net.encode(secret, Net.QUERY_RESP, targetName, h, v, String.join(",", alts)));
    }

    private void send(byte[] data) {
        Player carrier = null;
        for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
        if (carrier == null) return;
        try { carrier.sendPluginMessage(plugin, Net.CHANNEL, data); }
        catch (Throwable t) { plugin.getLogger().fine("Plugin message failed: " + t.getMessage()); }
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled || !Net.CHANNEL.equals(channel)) return;
        String[] parts = Net.decode(message, secret);
        if (parts == null) return;
        String type = parts[0];

        if (Net.QUERY.equals(type) && parts.length >= 2) {
            handleQuery(parts[1]);
        } else if (Net.QUERY_RESP.equals(type) && parts.length >= 5) {
            List<String> alts = parts[4].isEmpty()
                    ? new ArrayList<String>()
                    : new ArrayList<>(Arrays.asList(parts[4].split(",")));
            plugin.handleRemoteAltResponse(parts[1], parts[2], parts[3], alts);
        } else if (Net.ALERT.equals(type) && parts.length >= 5) {
            List<String> alts = parts[4].isEmpty()
                    ? new ArrayList<String>()
                    : new ArrayList<>(Arrays.asList(parts[4].split(",")));
            plugin.handleRemoteAlert(parts[1], parts[2], parts[3], alts);
        }
    }

    private void handleQuery(String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return;
        String[] live = plugin.getGrimSensitivity(target);
        if (live == null) return;
        String h = live[0], v = live[1];
        List<String> alts = plugin.getSensitivityManager().findAlts(h, v, targetName);
        plugin.getSensitivityManager().saveSensitivity(target.getUniqueId(), target.getName(), h, v);
        sendQueryResponse(targetName, h, v, alts);
    }
}
