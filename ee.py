
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adder.py — Adds shared MySQL storage + Bungee/Velocity relay to
MineStormAltingSystem.

After running:
  * bukkit (the plugin itself) stores sensitivities in a shared MySQL DB.
  * Only host / database / username / password live in config.yml —
    the schema is created by the plugin.
  * Cross-server: /alt <player> queries the whole network's alt list,
    whether the target is on this server, another server, or offline.
  * Alerts broadcast across the network via the proxy relay.

Run from the project root (where pom.xml is):
    python adder.py
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKUP = ROOT / "_backup_before_mysql"


def find_root(p: Path) -> Path:
    if (p / "pom.xml").exists() and (p / "src").is_dir():
        return p
    for c in p.iterdir():
        if c.is_dir() and (c / "pom.xml").exists() and (c / "src").is_dir():
            return c
    print("ERROR: run this inside the MineStormAltingSystem folder (where pom.xml is).")
    sys.exit(1)


def backup(path: Path) -> None:
    if not path.exists():
        return
    dst = BACKUP / path.relative_to(ROOT)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, dst)


def write(rel: str, content: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    backup(p)
    p.write_text(content, encoding="utf-8")
    print(f"  wrote {rel}")


def patch_pom(rel: str) -> None:
    p = ROOT / rel
    if not p.exists():
        print(f"  SKIP {rel} (missing)")
        return
    text = p.read_text(encoding="utf-8")
    changed = False

    deps = ""
    if "mysql-connector-j" not in text:
        deps += (
            "        <dependency>\n"
            "            <groupId>com.mysql</groupId>\n"
            "            <artifactId>mysql-connector-j</artifactId>\n"
            "            <version>8.0.33</version>\n"
            "        </dependency>\n"
            "        <dependency>\n"
            "            <groupId>com.zaxxer</groupId>\n"
            "            <artifactId>HikariCP</artifactId>\n"
            "            <version>4.0.3</version>\n"
            "        </dependency>\n"
        )
    if deps and "</dependencies>" in text:
        text = text.replace("</dependencies>", deps + "    </dependencies>", 1)
        changed = True

    if "com.zaxxer.hikari" not in text and "<relocation>" not in text:
        marker = "<createDependencyReducedPom>false</createDependencyReducedPom>"
        reloc = (
            "\n                            <relocations>\n"
            "                                <relocation>\n"
            "                                    <pattern>com.zaxxer.hikari</pattern>\n"
            "                                    <shadedPattern>me.minestorm.altingsystem.libs.hikari</shadedPattern>\n"
            "                                </relocation>\n"
            "                            </relocations>\n"
        )
        if marker in text:
            text = text.replace(marker, marker + reloc, 1)
            changed = True

    if changed:
        backup(p)
        p.write_text(text, encoding="utf-8")
        print(f"  patched {rel}")
    else:
        print(f"  {rel} already up to date")


def write_config(rel: str) -> None:
    p = ROOT / rel
    if not p.exists():
        print(f"  SKIP {rel} (missing)")
        return
    content = (
        "# ============================================================\n"
        "# MineStormAltingSystem — config.yml\n"
        "# ============================================================\n"
        "\n"
        "# MySQL — the plugin creates the database and tables itself.\n"
        "# Fill in the four values below. All servers must use the same ones.\n"
        "# Leave type: sqlite for a single-server setup.\n"
        "database:\n"
        "  type: mysql\n"
        "\n"
        "  # --- fill these in for MySQL ---\n"
        "  host: 127.0.0.1\n"
        "  database: minestormalt\n"
        "  username: root\n"
        "  password: ''\n"
        "\n"
        "  # --- optional overrides ---\n"
        "  port: 3306\n"
        "  useSSL: false\n"
        "  serverTimezone: UTC\n"
        "  poolSize: 10\n"
        "\n"
        "  # --- used only when type: sqlite ---\n"
        "  file: database.db\n"
        "\n"
        "  # How often this server re-reads the shared DB (seconds).\n"
        "  # Needed so /alt answers use fresh data from other servers.\n"
        "  refresh-interval-seconds: 5\n"
        "\n"
        "# --- cross-server network ---\n"
        "network:\n"
        "  # Set to true when a MineStormAltingSystem proxy plugin is installed\n"
        "  # on the Bungee / Velocity proxy.\n"
        "  proxy: true\n"
        "  # Unique name of THIS backend (must match the name used in the proxy config).\n"
        "  server-name: server\n"
        "  # Long random string, identical on every backend. Packets are HMAC-signed.\n"
        "  secret: change-me-to-a-long-random-string\n"
        "\n"
        "# --- general ---\n"
        "options:\n"
        "  # How long to wait before checking a joining player's sensitivity.\n"
        "  # Give GrimAC time to compute it.\n"
        "  join-check-delay-seconds: 15\n"
        "  # If a player is not on this server, look up their alt list from the shared DB\n"
        "  # (this is the /alt behaviour for players on other servers or offline).\n"
        "  allow-remote-lookup: true\n"
    )
    backup(p)
    p.write_text(content, encoding="utf-8")
    print(f"  wrote {rel}")


# ----------------------------------------------------------------------
# Storage.java — SQLite + MySQL, auto-creates DB and schema
# ----------------------------------------------------------------------
STORAGE_JAVA = r'''package me.minestorm.altingsystem.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.minestorm.altingsystem.MineStormAltingSystemPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared storage backend. SQLite (local file) or MySQL (shared across servers).
 *
 * With type: mysql the plugin connects using only host / database / username /
 * password from config.yml. If the database does not exist it is created
 * automatically along with the tables and indexes.
 */
public final class Storage {

    public enum Type { SQLITE, MYSQL }

    private final MineStormAltingSystemPlugin plugin;
    private final Type type;

    // SQLite
    private final File sqliteFile;

    // MySQL
    private final String jdbcUrl;
    private final String serverUrl;
    private final String dbName;
    private final String username;
    private final String password;

    private HikariDataSource pool;

    public Storage(MineStormAltingSystemPlugin plugin) {
        this.plugin = plugin;
        String t = plugin.getConfig().getString("database.type", "sqlite");
        this.type = t.equalsIgnoreCase("mysql") ? Type.MYSQL : Type.SQLITE;

        if (type == Type.SQLITE) {
            String name = plugin.getConfig().getString("database.file", "database.db");
            this.sqliteFile = new File(plugin.getDataFolder(), name);
            this.jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            this.serverUrl = null;
            this.dbName = null;
            this.username = null;
            this.password = null;
        } else {
            this.sqliteFile = null;
            String host = plugin.getConfig().getString("database.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("database.port", 3306);
            this.dbName = plugin.getConfig().getString("database.database", "minestormalt");
            this.username = plugin.getConfig().getString("database.username", "root");
            this.password = plugin.getConfig().getString("database.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("database.useSSL", false);
            String tz = plugin.getConfig().getString("database.serverTimezone", "UTC");

            String base = "jdbc:mysql://" + host + ":" + port;
            String opts = "?useSSL=" + useSSL
                    + "&serverTimezone=" + tz
                    + "&characterEncoding=utf8"
                    + "&useUnicode=true"
                    + "&allowPublicKeyRetrieval=true"
                    + "&autoReconnect=true"
                    + "&createDatabaseIfNotExist=true";
            this.serverUrl = base + "/" + opts;
            this.jdbcUrl = base + "/" + dbName + opts;
        }
    }

    public Type getType() { return type; }

    public void init() throws SQLException {
        if (type == Type.MYSQL) ensureDatabaseExists();
        open();
        createSchema();
    }

    private void open() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setPoolName("MineStormAltingSystem-" + type.name());
        cfg.setLeakDetectionThreshold(60000L);

        if (type == Type.MYSQL) {
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(plugin.getConfig().getInt("database.poolSize", 10));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10000L);
            cfg.setIdleTimeout(600000L);
            cfg.setMaxLifetime(1800000L);
        } else {
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1);
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        }

        pool = new HikariDataSource(cfg);
    }

    private void ensureDatabaseExists() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MySQL driver not found", ex);
        }
        try (Connection c = DriverManager.getConnection(serverUrl, username, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not auto-create database '" + dbName + "': " + ex.getMessage());
        }
    }

    private void createSchema() throws SQLException {
        String engineSuffix = type == Type.MYSQL
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                : "";
        try (Connection c = pool.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sensitivities ("
                    + " uuid VARCHAR(36) PRIMARY KEY,"
                    + " name VARCHAR(16) NOT NULL,"
                    + " hSens VARCHAR(32) NOT NULL,"
                    + " vSens VARCHAR(32) NOT NULL,"
                    + " updated BIGINT NOT NULL DEFAULT 0,"
                    + " INDEX idx_sens (hSens, vSens),"
                    + " INDEX idx_name (name))" + engineSuffix);
        }
    }

    public Connection connection() throws SQLException {
        if (pool == null) throw new SQLException("Storage not initialised");
        return pool.getConnection();
    }

    public void close() {
        if (pool != null && !pool.isClosed()) pool.close();
        pool = null;
    }
}
'''


# ----------------------------------------------------------------------
# SensitivityManager.java — rewritten to use Storage
# ----------------------------------------------------------------------
SENSITIVITY_MANAGER_JAVA = r'''package me.minestorm.altingsystem.managers;

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
 *
 * With MySQL every server sees the same alt list, so /alt answers "which
 * accounts share this sensitivity?" across the whole network. A refresher
 * re-reads the DB periodically so changes made on other servers appear here
 * without a restart.
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
        try {
            storage.init();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not init storage", e);
        }
        loadCacheFromDatabase();
        startPeriodicSave();
        startRefresh();
    }

    public Storage.Type getType() { return storage.getType(); }

    public void migrateFromYaml() {
        File yamlFile = new File(plugin.getDataFolder(), "sensitivities.yml");
        if (!yamlFile.exists()) return;

        plugin.getLogger().info("Found sensitivities.yml! Migrating data to storage...");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = 0;
            for (String uuidStr : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(uuidStr);
                if (section == null) continue;
                String name = section.getString("name", "Unknown");
                String hSens = section.getString("hSens", "N/A");
                String vSens = section.getString("vSens", "N/A");
                UUID uuid;
                try { uuid = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }
                saveSensitivityToDatabase(uuid, name, hSens, vSens);
                if (isValid(hSens) && isValid(vSens)) {
                    sensitivityCache.put(uuid, new SensitivityData(name, hSens, vSens));
                }
                count++;
            }
            plugin.getLogger().info("Migrated " + count + " players to shared storage.");
            File migrated = new File(plugin.getDataFolder(), "sensitivities.yml.old");
            if (!yamlFile.renameTo(migrated))
                plugin.getLogger().warning("Could not rename sensitivities.yml after migration.");
        });
    }

    // ------------------------------------------------------------------
    // public API — same shape as before
    // ------------------------------------------------------------------

    public void saveSensitivity(UUID uuid, String name, String hSens, String vSens) {
        if (!isValid(hSens) || !isValid(vSens)) return;
        sensitivityCache.put(uuid, new SensitivityData(name, hSens, vSens));
        // Fire-and-forget write so a server crash doesn't lose recent data
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> saveSensitivityToDatabase(uuid, name, hSens, vSens));
    }

    public String[] getOfflineSensitivity(String name) {
        for (SensitivityData data : sensitivityCache.values()) {
            if (data.name.equalsIgnoreCase(name)) return new String[]{data.hSens, data.vSens};
        }
        return null;
    }

    public List<String> findAlts(String hSens, String vSens, String excludeName) {
        List<String> alts = new ArrayList<>();
        if (!isValid(hSens) || !isValid(vSens)) return alts;
        for (SensitivityData data : sensitivityCache.values()) {
            if (data.hSens.equals(hSens) && data.vSens.equals(vSens)
                    && !data.name.equalsIgnoreCase(excludeName)) alts.add(data.name);
        }
        return alts;
    }

    public Map<String, List<String>> getSensitivityGroups() {
        Map<String, List<String>> groups = new HashMap<>();
        for (SensitivityData data : sensitivityCache.values()) {
            if (isValid(data.hSens) && isValid(data.vSens)) {
                groups.computeIfAbsent(data.hSens + " | " + data.vSens, k -> new ArrayList<>())
                        .add(data.name);
            }
        }
        return groups;
    }

    public boolean isValid(String sens) {
        if (sens == null || "N/A".equals(sens)) return false;
        return !(sens.equals("0") || sens.equals("0.0") || sens.equals("0%") || sens.equals("0.0%"));
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    private String upsertSql() {
        return storage.getType() == Storage.Type.MYSQL
                ? "INSERT INTO sensitivities (uuid, name, hSens, vSens, updated) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), hSens=VALUES(hSens), "
                + "vSens=VALUES(vSens), updated=VALUES(updated)"
                : "INSERT OR REPLACE INTO sensitivities (uuid, name, hSens, vSens, updated) VALUES (?,?,?,?,?)";
    }

    private void saveSensitivityToDatabase(UUID uuid, String name, String hSens, String vSens) {
        if (!isValid(hSens) || !isValid(vSens)) return;
        try (Connection c = storage.connection();
             PreparedStatement ps = c.prepareStatement(upsertSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, hSens);
            ps.setString(4, vSens);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save sensitivity for " + name, e);
        }
    }

    private void loadCacheFromDatabase() {
        try (Connection c = storage.connection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uuid, name, hSens, vSens FROM sensitivities");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    sensitivityCache.put(uuid, new SensitivityData(
                            rs.getString(2), rs.getString(3), rs.getString(4)));
                } catch (IllegalArgumentException ignored) { }
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
            boolean originalAuto = c.getAutoCommit();
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
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                try { c.setAutoCommit(originalAuto); } catch (SQLException ignored) { }
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
        final String name;
        final String hSens;
        final String vSens;
        SensitivityData(String name, String hSens, String vSens) {
            this.name = name; this.hSens = hSens; this.vSens = vSens;
        }
    }
}
'''


# ----------------------------------------------------------------------
# Net.java — wire protocol (shared between Bukkit and proxy)
# ----------------------------------------------------------------------
NET_JAVA = r'''package me.minestorm.altingsystem.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Wire format:  [32 byte HMAC-SHA256][UTF type][byte argc][UTF arg]*
 *
 * The proxy only relays the raw bytes. Backends verify the HMAC with the
 * shared secret from config.yml, so a client can never forge a packet.
 * Channel name is short + namespaced: valid on 1.8 (<=20 chars) and on 1.13+.
 */
public final class Net {
    public static final String CHANNEL = "msalt:main";

    // Outgoing from backend
    public static final String QUERY      = "QUERY";       // targetName
    public static final String QUERY_RESP = "QUERY_RESP";  // targetName, hSens, vSens, altCsv
    public static final String ALERT      = "ALERT";       // suspect, hSens, vSens, altCsv

    private Net() {}

    public static byte[] encode(String secret, String type, String... args) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.writeUTF(type);
            out.writeByte(args.length);
            for (String a : args) out.writeUTF(a == null ? "" : a);
            byte[] payload = body.toByteArray();

            ByteArrayOutputStream full = new ByteArrayOutputStream();
            full.write(mac(secret, payload));
            full.write(payload);
            return full.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return {type, args...} or null if invalid. */
    public static String[] decode(byte[] data, String secret) {
        if (data == null || data.length <= 32) return null;
        byte[] mac = Arrays.copyOfRange(data, 0, 32);
        byte[] body = Arrays.copyOfRange(data, 32, data.length);
        if (!MessageDigest.isEqual(mac, mac(secret, body))) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            String type = in.readUTF();
            int n = in.readUnsignedByte();
            String[] out = new String[n + 1];
            out[0] = type;
            for (int i = 0; i < n; i++) out[i + 1] = in.readUTF();
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] mac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
'''


# ----------------------------------------------------------------------
# ProxyBridge.java — Bukkit side of the cross-server link
# ----------------------------------------------------------------------
PROXY_BRIDGE_JAVA = r'''package me.minestorm.altingsystem.net;

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
'''


# ----------------------------------------------------------------------
# MineStormAltingSystemPlugin.java — wire in Storage + bridge
# ----------------------------------------------------------------------
PLUGIN_JAVA = r'''package me.minestorm.altingsystem;

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
'''


# ----------------------------------------------------------------------
# AltCommand.java — supports remote lookup + "which alts does X have"
# ----------------------------------------------------------------------
ALT_COMMAND_JAVA = r'''package me.minestorm.altingsystem.commands;

import me.minestorm.altingsystem.MineStormAltingSystemPlugin;
import me.minestorm.altingsystem.managers.SensitivityManager;
import me.minestorm.altingsystem.util.ChatTheme;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AltCommand implements CommandExecutor, TabCompleter {

    private final MineStormAltingSystemPlugin plugin;
    private final SensitivityManager manager;

    public AltCommand(MineStormAltingSystemPlugin plugin, SensitivityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minestorm.alt")) {
            sender.sendMessage(ChatTheme.error("You do not have permission to use this command."));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Usage: " + ChatColor.AQUA + "/alt <player>");
            return true;
        }

        final String requested = args[0];
        final Player localTarget = Bukkit.getPlayerExact(requested);
        final String displayName = localTarget != null ? localTarget.getName() : requested;
        final boolean localOnline = localTarget != null;

        // 1. If the player is on this server, use live Grim data.
        if (localOnline && plugin.isTrackingEnabled()) {
            String[] live = plugin.getGrimSensitivity(localTarget);
            if (live != null && manager.isValid(live[0]) && manager.isValid(live[1])) {
                List<String> alts = manager.findAlts(live[0], live[1], displayName);
                manager.saveSensitivity(localTarget.getUniqueId(), displayName, live[0], live[1]);
                printResult(sender, displayName, localOnline, live[0], live[1], alts,
                        ChatColor.GREEN + "Live (GrimAC)");
                return true;
            }
        }

        // 2. Otherwise fall back to shared DB cache (works for other servers + offline).
        if (plugin.getConfig().getBoolean("options.allow-remote-lookup", true)) {
            String[] cached = manager.getOfflineSensitivity(displayName);
            if (cached != null) {
                List<String> alts = manager.findAlts(cached[0], cached[1], displayName);
                printResult(sender, displayName, localOnline, cached[0], cached[1], alts,
                        ChatColor.GRAY + "Shared DB");
                return true;
            }
        }

        // 3. If the proxy is up and we don't know the player, ask the network.
        if (plugin.getProxyBridge() != null && plugin.getProxyBridge().isEnabled()) {
            sender.sendMessage(ChatTheme.PREFIX + ChatColor.GRAY + "Querying network for "
                    + ChatColor.AQUA + displayName + ChatColor.GRAY + "...");
            plugin.awaitRemoteAlt(displayName, (sens, alts) -> {
                if (sens == null) {
                    sender.sendMessage(ChatTheme.error("No data for " + displayName
                            + " on any server. He may never have been seen by GrimAC."));
                } else {
                    printResult(sender, displayName, localOnline, sens[0], sens[1],
                            alts == null ? new ArrayList<>() : alts,
                            ChatColor.GRAY + "Remote (network)");
                }
            });
            plugin.getProxyBridge().broadcastQuery(displayName);
            return true;
        }

        if (localOnline) {
            sender.sendMessage(ChatTheme.error(displayName + " has no valid sensitivity yet"
                    + (plugin.isTrackingEnabled() ? " - GrimAC may still be calculating it."
                    : " and GrimAC tracking is currently disabled.")));
        } else {
            sender.sendMessage(ChatTheme.error(displayName + " is offline and has no saved sensitivity data."));
        }
        return true;
    }

    private void printResult(CommandSender sender, String name, boolean online,
                             String hSens, String vSens, List<String> alts, String source) {
        ChatTheme.sendHeader(sender, "ALT CHECK", name + " " + ChatTheme.tagOnline(online));
        ChatTheme.sendField(sender, "Horizontal", hSens);
        ChatTheme.sendField(sender, "Vertical", vSens);
        ChatTheme.sendField(sender, "Source", source);
        sender.sendMessage("");
        if (alts.isEmpty()) {
            sender.sendMessage("  " + ChatTheme.tagClean() + ChatColor.GRAY + " No matching accounts found.");
        } else {
            sender.sendMessage("  " + ChatTheme.tagAlt(alts.size()));
            sender.sendMessage("  " + ChatColor.AQUA + "Matches" + ChatColor.DARK_GRAY + ": "
                    + ChatTheme.formatAltNames(alts));
        }
        ChatTheme.sendFooter(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("minestorm.alt")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
'''


# ----------------------------------------------------------------------
# SensitivityListener.java — broadcast alerts across the network
# ----------------------------------------------------------------------
LISTENER_JAVA = r'''package me.minestorm.altingsystem.listeners;

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
'''


# ----------------------------------------------------------------------
# Proxy relay — Bungee + Velocity
# ----------------------------------------------------------------------
BUNGEE_JAVA = r'''package me.minestorm.altingsystem.bungee;

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
'''

VELOCITY_JAVA = r'''package me.minestorm.altingsystem.velocity;

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
'''


# ----------------------------------------------------------------------
# Build the module tree
# ----------------------------------------------------------------------
def main():
    global ROOT
    ROOT = find_root(Path.cwd())

    print("=" * 60)
    print(" MineStormAltingSystem — adder")
    print(" Project: " + str(ROOT))
    print(" Backups: " + str(BACKUP))
    print("=" * 60)

    # 1. Maven: add MySQL + HikariCP, add relocation
    patch_pom("pom.xml")

    # 2. config.yml
    write_config("src/main/resources/config.yml")

    # 3. Java files
    base = "src/main/java/me/minestorm/altingsystem"
    write(f"{base}/storage/Storage.java", STORAGE_JAVA)
    write(f"{base}/managers/SensitivityManager.java", SENSITIVITY_MANAGER_JAVA)
    write(f"{base}/net/Net.java", NET_JAVA)
    write(f"{base}/net/ProxyBridge.java", PROXY_BRIDGE_JAVA)
    write(f"{base}/MineStormAltingSystemPlugin.java", PLUGIN_JAVA)
    write(f"{base}/commands/AltCommand.java", ALT_COMMAND_JAVA)
    write(f"{base}/listeners/SensitivityListener.java", LISTENER_JAVA)

    # 4. Bungee module
    write("bungee/src/main/java/me/minestorm/altingsystem/bungee/MineStormAltingSystemBungee.java",
          BUNGEE_JAVA)
    write("bungee/src/main/resources/bungee.yml",
          "name: MineStormAltingSystem\n"
          "main: me.minestorm.altingsystem.bungee.MineStormAltingSystemBungee\n"
          "version: 1.0.0\n"
          "author: MineStorm\n"
          "description: Cross-server sensitivity relay\n")

    # 5. Velocity module
    write("velocity/src/main/java/me/minestorm/altingsystem/velocity/MineStormAltingSystemVelocity.java",
          VELOCITY_JAVA)

    print("=" * 60)
    print(" Done.")
    print()
    print(" Next:")
    print("  1) Edit src/main/resources/config.yml")
    print("     and set database.host / database / username / password.")
    print("     The plugin creates the DB and tables itself.")
    print("  2) mvn clean package")
    print("  3) Copy the plugin jar to every backend and the proxy jar to Bungee/Velocity.")
    print()
    print(" Commands:")
    print("   /alt <player>     -> shows sensitivities AND every account that shares them")
    print("                        (live from GrimAC if local, shared DB if remote, network query otherwise)")
    print("   /altreport        -> all sensitivity groups (shared across the network)")
    print("   /alttoggle        -> toggle your own alerts")
    print()
    print(" Originals are in _backup_before_mysql/")
    print("=" * 60)


if __name__ == "__main__":
    main()
