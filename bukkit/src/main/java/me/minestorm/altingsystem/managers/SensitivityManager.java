package me.minestorm.altingsystem.managers;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Reads/writes sensitivities to a shared storage (SQLite or MySQL).
 * With MySQL every server sees the same alt list. A periodic refresher
 * re-reads the DB so changes made on other servers appear here.
 */
public class SensitivityManager {

    private final MineStormAltingSystemPlugin plugin;
    private final Storage storage;
    private final Map<UUID, SensitivityData> sensitivityCache = new ConcurrentHashMap<>();
    private BukkitTask saveTask;
    private BukkitTask refreshTask;

    public SensitivityManager(MineStormAltingSystemPlugin plugin) {
        this.plugin = plugin;
        this.storage = new Storage(plugin);
        try { storage.init(); }
        catch (SQLException e) { plugin.getLogger().log(Level.SEVERE, "Could not init storage", e); }
        loadCacheFromDatabase();
        startPeriodicSave();
        startRefresh();
    }

    public Storage.Type getType() { return storage.getType(); }

    /** Imports a legacy sensitivities.yml (if present). */
    public void migrateFromYaml() {
        File yamlFile = new File(plugin.getDataFolder(), "sensitivities.yml");
        if (!yamlFile.exists()) return;
        plugin.getLogger().info("Found sensitivities.yml! Migrating to shared storage...");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yamlFile);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = 0;
            for (String key : cfg.getKeys(false)) {
                ConfigurationSection sec = cfg.getConfigurationSection(key);
                if (sec == null) continue;
                String name = sec.getString("name", "Unknown");
                String h = sec.getString("hSens", "N/A");
                String v = sec.getString("vSens", "N/A");
                UUID uuid;
                try { uuid = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
                saveSensitivityToDatabase(uuid, name, h, v);
                if (isValid(h) && isValid(v)) sensitivityCache.put(uuid, new SensitivityData(name, h, v));
                count++;
            }
            plugin.getLogger().info("Migrated " + count + " players to shared storage.");
            File old = new File(plugin.getDataFolder(), "sensitivities.yml.old");
            if (!yamlFile.renameTo(old)) plugin.getLogger().warning("Could not rename sensitivities.yml");
        });
    }

    public void saveSensitivity(UUID uuid, String name, String hSens, String vSens) {
        if (!isValid(hSens) || !isValid(vSens)) return;
        sensitivityCache.put(uuid, new SensitivityData(name, hSens, vSens));
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> saveSensitivityToDatabase(uuid, name, hSens, vSens));
    }

    public String[] getOfflineSensitivity(String name) {
        for (SensitivityData d : sensitivityCache.values()) {
            if (d.name.equalsIgnoreCase(name)) return new String[]{ d.hSens, d.vSens };
        }
        return null;
    }

    public List<String> findAlts(String hSens, String vSens, String excludeName) {
        List<String> alts = new ArrayList<>();
        if (!isValid(hSens) || !isValid(vSens)) return alts;
        for (SensitivityData d : sensitivityCache.values()) {
            if (d.hSens.equals(hSens) && d.vSens.equals(vSens)
                    && !d.name.equalsIgnoreCase(excludeName)) alts.add(d.name);
        }
        return alts;
    }

    public Map<String, List<String>> getSensitivityGroups() {
        Map<String, List<String>> groups = new HashMap<>();
        for (SensitivityData d : sensitivityCache.values()) {
            if (isValid(d.hSens) && isValid(d.vSens)) {
                groups.computeIfAbsent(d.hSens + " | " + d.vSens, k -> new ArrayList<>()).add(d.name);
            }
        }
        return groups;
    }

    public boolean isValid(String sens) {
        if (sens == null || "N/A".equals(sens)) return false;
        return !(sens.equals("0") || sens.equals("0.0") || sens.equals("0%") || sens.equals("0.0%"));
    }

    private String upsertSql() {
        return storage.getType() == Storage.Type.MYSQL
                ? "INSERT INTO sensitivities (uuid, name, hSens, vSens, updated) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), hSens=VALUES(hSens), "
                + "vSens=VALUES(vSens), updated=VALUES(updated)"
                : "INSERT OR REPLACE INTO sensitivities (uuid, name, hSens, vSens, updated) VALUES (?,?,?,?,?)";
    }

    private void saveSensitivityToDatabase(UUID uuid, String name, String h, String v) {
        if (!isValid(h) || !isValid(v)) return;
        try (Connection c = storage.connection();
             PreparedStatement ps = c.prepareStatement(upsertSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, h);
            ps.setString(4, v);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save sensitivity for " + name, e);
        }
    }

    private void loadCacheFromDatabase() {
        try (Connection c = storage.connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, hSens, vSens FROM sensitivities");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    sensitivityCache.put(uuid, new SensitivityData(
                            rs.getString(2), rs.getString(3), rs.getString(4)));
                } catch (IllegalArgumentException ignored) {}
            }
            plugin.getLogger().info("Loaded " + sensitivityCache.size()
                    + " sensitivity records (" + storage.getType() + ").");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load sensitivity cache", e);
        }
    }

    private void startPeriodicSave() {
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                this::flushCacheToDatabase, 36000L, 36000L);
    }

    private void startRefresh() {
        if (storage.getType() != Storage.Type.MYSQL) return;
        int sec = plugin.getConfig().getInt("database.refresh-interval-seconds", 5);
        if (sec <= 0) return;
        long ticks = sec * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                this::loadCacheFromDatabase, ticks, ticks);
        plugin.getLogger().info("Sensitivity cache refresher started (every " + sec + "s).");
    }

    private void flushCacheToDatabase() {
        if (sensitivityCache.isEmpty()) return;
        Map<UUID, SensitivityData> snapshot = new HashMap<>(sensitivityCache);
        try (Connection c = storage.connection()) {
            boolean original = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(upsertSql())) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<UUID, SensitivityData> e : snapshot.entrySet()) {
                        SensitivityData d = e.getValue();
                        ps.setString(1, e.getKey().toString());
                        ps.setString(2, d.name);
                        ps.setString(3, d.hSens);
                        ps.setString(4, d.vSens);
                        ps.setLong(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(original); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to flush sensitivity cache", e);
        }
    }

    public void close() {
        if (saveTask != null) saveTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        flushCacheToDatabase();
        storage.close();
    }

    private static final class SensitivityData {
        final String name, hSens, vSens;
        SensitivityData(String n, String h, String v) { this.name = n; this.hSens = h; this.vSens = v; }
    }
}
