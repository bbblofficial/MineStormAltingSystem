package me.minestorm.altingsystem.net;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-server link through the Bungee / Velocity relay. Sends QUERY / ALERT,
 * receives QUERY_RESP from whichever server hosts the target.
 */
public final class ProxyBridge implements PluginMessageListener {

    private final MineStormAltingSystemPlugin plugin;
    private boolean enabled;
    private String secret;

    public ProxyBridge(MineStormAltingSystemPlugin plugin) {
        this.plugin = plugin;
    }

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

    /** Ask the network about a player. Whichever server hosts him will answer. */
    public void broadcastQuery(String targetName) {
        if (!enabled) return;
        send(Net.encode(secret, Net.QUERY, targetName));
    }

    public void broadcastAlert(String suspect, String hSens, String vSens, List<String> alts) {
        if (!enabled) return;
        send(Net.encode(secret, Net.ALERT, suspect, hSens, vSens, String.join(",", alts)));
    }

    public void sendQueryResponse(String targetName, String hSens, String vSens, List<String> alts) {
        if (!enabled) return;
        send(Net.encode(secret, Net.QUERY_RESP, targetName, hSens, vSens, String.join(",", alts)));
    }

    private void send(byte[] data) {
        Player carrier = null;
        for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
        if (carrier == null) return;
        try {
            carrier.sendPluginMessage(plugin, Net.CHANNEL, data);
        } catch (Throwable t) {
            plugin.getLogger().fine("Plugin message failed: " + t.getMessage());
        }
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled || !Net.CHANNEL.equals(channel)) return;
        String[] parts = Net.decode(message, secret);
        if (parts == null) return;
        String type = parts[0];

        if (Net.QUERY.equals(type) && parts.length >= 2) {
            // A server is asking about a player. If we host him, reply.
            handleQuery(parts[1]);
        } else if (Net.QUERY_RESP.equals(type) && parts.length >= 5) {
            String target = parts[1], hSens = parts[2], vSens = parts[3];
            List<String> alts = parts[4].isEmpty()
                    ? new ArrayList<String>() : new ArrayList<>(java.util.Arrays.asList(parts[4].split(",")));
            plugin.handleRemoteAltResponse(target, hSens, vSens, alts);
        } else if (Net.ALERT.equals(type) && parts.length >= 5) {
            List<String> alts = parts[4].isEmpty()
                    ? new ArrayList<String>() : new ArrayList<>(java.util.Arrays.asList(parts[4].split(",")));
            plugin.handleRemoteAlert(parts[1], parts[2], parts[3], alts);
        }
    }

    private void handleQuery(String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return;
        String[] live = plugin.getGrimSensitivity(target);
        if (live == null) return;
        String hSens = live[0], vSens = live[1];
        List<String> alts = plugin.getSensitivityManager().findAlts(hSens, vSens, targetName);
        plugin.getSensitivityManager().saveSensitivity(target.getUniqueId(), target.getName(), hSens, vSens);
        sendQueryResponse(targetName, hSens, vSens, alts);
    }
}
