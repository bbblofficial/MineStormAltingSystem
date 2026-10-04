package me.minestorm.altingsystem;

import me.minestorm.altingsystem.commands.AltCommand;
import me.minestorm.altingsystem.commands.AltReportCommand;
import me.minestorm.altingsystem.commands.AltToggleCommand;
import me.minestorm.altingsystem.listeners.SensitivityListener;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.net.ProxyBridge;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class MineStormAltingSystemPlugin extends JavaPlugin {

    public static final String GRIM_PLUGIN_NAME = "GrimAC";
    public static final String ALERT_PERMISSION = "minestorm.alerts";
    private static final String GRIM_API_CLASS = "ac.grim.grimac.api.GrimAbstractAPI";

    private SensitivityManager sensitivityManager;
    private ProxyBridge proxyBridge;
    private final Set<UUID> disabledAlerts = ConcurrentHashMap.newKeySet();

    private volatile boolean trackingEnabled = false;
    private Object grimApi;
    private Method getGrimUserMethod;
    private Method getReplacementsMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        sensitivityManager = new SensitivityManager(this);
        sensitivityManager.migrateFromYaml();

        proxyBridge = new ProxyBridge(this);
        proxyBridge.enable();

        registerCommands();
        getServer().getPluginManager().registerEvents(
                new SensitivityListener(this, sensitivityManager), this);

        boolean ok = initGrim();
        trackingEnabled = ok;
        if (ok) {
            getLogger().info("GrimAC API detected. Sensitivity tracking is active.");
        } else {
            handleGrimFailure();
            Bukkit.getScheduler().runTaskLater(this, this::recheckGrim, 200L);
        }

        getLogger().info("MineStormAltingSystem has been enabled."
                + " Storage: " + sensitivityManager.getType()
                + (proxyBridge.isEnabled() ? " | cross-server enabled" : ""));
    }

    @Override
    public void onDisable() {
        trackingEnabled = false;
        if (proxyBridge != null) proxyBridge.disable();
        if (sensitivityManager != null) sensitivityManager.close();
        getLogger().info("MineStormAltingSystem has been disabled.");
    }

    private void registerCommands() {
        PluginCommand alt = getCommand("alt");
        if (alt != null) {
            AltCommand exec = new AltCommand(this, sensitivityManager);
            alt.setExecutor(exec);
            alt.setTabCompleter(exec);
        }
        PluginCommand altReport = getCommand("altreport");
        if (altReport != null) altReport.setExecutor(new AltReportCommand(sensitivityManager));
        PluginCommand altToggle = getCommand("alttoggle");
        if (altToggle != null) altToggle.setExecutor(new AltToggleCommand(this));
    }

    // ------------------------------------------------------------------
    // public API used by commands / bridge
    // ------------------------------------------------------------------
    public SensitivityManager getSensitivityManager() { return sensitivityManager; }
    public ProxyBridge getProxyBridge() { return proxyBridge; }
    public boolean isTrackingEnabled() { return trackingEnabled; }

    public boolean hasAlertsDisabled(UUID uuid) { return disabledAlerts.contains(uuid); }
    public void setAlertsDisabled(UUID uuid, boolean disabled) {
        if (disabled) disabledAlerts.add(uuid); else disabledAlerts.remove(uuid);
    }

    /**
     * Called by the bridge when another server replied to a QUERY.
     * The original requester listens for a specific player name; commands use
     * a small in-memory waiter list.
     */
    private final Map<String, java.util.function.BiConsumer<String[], List<String>>> pendingQueries
            = new ConcurrentHashMap<>();

    /** Register interest in a QUERY response. Reply is (sens[2], alts). */
    public void awaitRemoteAlt(String targetName,
                               java.util.function.BiConsumer<String[], List<String>> callback) {
        pendingQueries.put(targetName.toLowerCase(), callback);
        // Timeout after 2s -> give up
        Bukkit.getScheduler().runTaskLater(this, () -> {
            java.util.function.BiConsumer<String[], List<String>> removed =
                    pendingQueries.remove(targetName.toLowerCase());
            if (removed != null) removed.accept(null, null);
        }, 40L);
    }

    public void handleRemoteAltResponse(String targetName, String hSens, String vSens, List<String> alts) {
        java.util.function.BiConsumer<String[], List<String>> callback =
                pendingQueries.remove(targetName.toLowerCase());
        if (callback != null) {
            callback.accept(new String[]{hSens, vSens}, alts);
        }
    }

    public void handleRemoteAlert(String suspect, String hSens, String vSens, List<String> alts) {
        // Re-broadcast to local staff only
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(ALERT_PERMISSION) && !hasAlertsDisabled(p.getUniqueId())) {
                p.sendMessage(new String[]{
                        ChatTheme.divider(),
                        ChatTheme.PREFIX + ChatColor.RED + ChatColor.BOLD + "ALT ALERT",
                        "  " + ChatColor.AQUA + "Player" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + suspect,
                        "  " + ChatColor.AQUA + "Sensitivity" + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE
                                + hSens + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + vSens,
                        "  " + ChatColor.AQUA + "Matches" + ChatColor.DARK_GRAY + ": " + ChatTheme.formatAltNames(alts),
                        ChatTheme.divider()
                });
            }
        }
    }

    // ------------------------------------------------------------------
    // Grim detection (unchanged)
    // ------------------------------------------------------------------
    private boolean initGrim() {
        grimApi = null; getGrimUserMethod = null; getReplacementsMethod = null;
        try {
            Plugin grim = getServer().getPluginManager().getPlugin(GRIM_PLUGIN_NAME);
            if (grim == null || !grim.isEnabled()) return false;
            Class<?> apiClass = Class.forName(GRIM_API_CLASS, true, grim.getClass().getClassLoader());
            RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(apiClass);
            if (reg == null) return false;
            Object api = reg.getProvider();
            if (api == null) return false;
            Method getUser = findMethod(apiClass, api, "getGrimUser", UUID.class);
            if (getUser == null) return false;
            grimApi = api;
            getGrimUserMethod = getUser;
            getReplacementsMethod = findMethod(apiClass, api, "getVariableReplacements");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().warning("Could not reach the GrimAC API: " + e);
            return false;
        }
    }

    private Method findMethod(Class<?> apiClass, Object api, String name, Class<?>... params) {
        try { return apiClass.getMethod(name, params); }
        catch (NoSuchMethodException ignored) {}
        try {
            Method m = api.getClass().getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException ignored) { return null; }
    }

    private void recheckGrim() {
        if (trackingEnabled) return;
        if (initGrim()) {
            trackingEnabled = true;
            getLogger().info("GrimAC API is now available. Sensitivity tracking ENABLED.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp() || p.hasPermission(ALERT_PERMISSION)) {
                    p.sendMessage(ChatTheme.PREFIX + ChatColor.GREEN + "GrimAC detected - sensitivity tracking active.");
                }
            }
        } else {
            getLogger().warning("GrimAC is still unavailable. Sensitivity tracking remains disabled.");
        }
    }

    private void handleGrimFailure() {
        getLogger().severe("==================================================");
        getLogger().severe("GrimAC is missing, disabled, or its API cannot be reached.");
        getLogger().severe("Sensitivity tracking and alt alerts have been DISABLED.");
        getLogger().severe("Install/enable GrimAC and restart the server.");
        getLogger().severe("==================================================");
        for (Player p : Bukkit.getOnlinePlayers()) sendGrimProblem(p);
    }

    public void sendGrimProblem(Player p) {
        if (!p.isOp() && !p.hasPermission(ALERT_PERMISSION)) return;
        p.sendMessage(ChatTheme.divider());
        p.sendMessage(ChatTheme.PREFIX + ChatColor.RED + ChatColor.BOLD + "GrimAC NOT DETECTED");
        p.sendMessage("  " + ChatColor.GRAY + "The GrimAC API is unavailable, so "
                + ChatColor.AQUA + "sensitivity tracking is disabled" + ChatColor.GRAY + ".");
        p.sendMessage("  " + ChatColor.GRAY + "Cached data can still be viewed with "
                + ChatColor.AQUA + "/alt" + ChatColor.GRAY + " and " + ChatColor.AQUA + "/altreport"
                + ChatColor.GRAY + ".");
        p.sendMessage(ChatTheme.divider());
    }

    @SuppressWarnings("unchecked")
    public String[] getGrimSensitivity(Player player) {
        if (!trackingEnabled || grimApi == null || getGrimUserMethod == null) return null;
        try {
            Object grimUser = getGrimUserMethod.invoke(grimApi, player.getUniqueId());
            if (grimUser == null) return null;
            String hSens = null, vSens = null;
            if (getReplacementsMethod != null) {
                Object replacements = getReplacementsMethod.invoke(grimApi);
                if (replacements instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) replacements).entrySet()) {
                        if (!(e.getKey() instanceof String) || !(e.getValue() instanceof Function)) continue;
                        String key = (String) e.getKey();
                        Function<Object, Object> fn = (Function<Object, Object>) e.getValue();
                        if (key.contains("h_sensitivity")) hSens = String.valueOf(fn.apply(grimUser));
                        else if (key.contains("v_sensitivity")) vSens = String.valueOf(fn.apply(grimUser));
                    }
                }
            }
            if (hSens == null) hSens = readDirectSensitivity(grimUser, "getHorizontalSensitivity");
            if (vSens == null) vSens = readDirectSensitivity(grimUser, "getVerticalSensitivity");
            return new String[]{hSens != null ? hSens : "N/A", vSens != null ? vSens : "N/A"};
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private String readDirectSensitivity(Object grimUser, String methodName) {
        try {
            Method m = grimUser.getClass().getMethod(methodName);
            try { m.setAccessible(true); } catch (RuntimeException ignored) {}
            Object v = m.invoke(grimUser);
            if (v instanceof Number) return Math.round(((Number) v).doubleValue() * 200.0D) + "%";
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return null;
    }
}
