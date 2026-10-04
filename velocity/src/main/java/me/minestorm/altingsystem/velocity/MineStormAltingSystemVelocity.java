package me.minestorm.altingsystem.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

/**
 * MineStormAltingSystem proxy relay (Velocity) — stateless.
 */
@Plugin(
        id = "minestormaltingsystem",
        name = "MineStormAltingSystem",
        version = "1.0.0",
        description = "Cross-server sensitivity relay",
        authors = {"MineStorm"}
)
public final class MineStormAltingSystemVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("msalt:main");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MineStormAltingSystemVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MineStormAltingSystem-Velocity enabled.");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getIdentifier().equals(CHANNEL)) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(e.getSource() instanceof ServerConnection)) return;

        String origin = ((ServerConnection) e.getSource()).getServerInfo().getName();
        byte[] data = e.getData();
        for (RegisteredServer rs : server.getAllServers()) {
            if (rs.getServerInfo().getName().equals(origin)) continue;
            if (rs.getPlayersConnected().isEmpty()) continue;
            rs.sendPluginMessage(CHANNEL, data);
        }
    }
}
