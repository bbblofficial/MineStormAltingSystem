package me.minestorm.altingsystem.bungee;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * MineStormAltingSystem proxy relay (BungeeCord) — stateless.
 * Forwards any packet sent by a backend to every OTHER backend.
 */
public class MineStormAltingSystemBungee extends Plugin implements Listener {

    public static final String CHANNEL = "msalt:main";

    @Override
    public void onEnable() {
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormAltingSystem-Bungee enabled.");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getTag())) return;
        e.setCancelled(true); // never leak to clients
        if (!(e.getSender() instanceof Server)) return;

        Server origin = (Server) e.getSender();
        byte[] data = e.getData();
        for (ServerInfo info : getProxy().getServers().values()) {
            if (info.getName().equals(origin.getInfo().getName())) continue;
            if (info.getPlayers().isEmpty()) continue; // needs a carrier player
            info.sendData(CHANNEL, data, false);
        }
    }
}
