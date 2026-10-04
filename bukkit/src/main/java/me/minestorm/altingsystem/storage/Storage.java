package me.minestorm.altingsystem.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.minestorm.altingsystem.MineStormAltingSystemPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared storage. SQLite (local file) or MySQL (shared across servers).
 * With type: mysql the database and tables are created automatically.
 */
public final class Storage {

    public enum Type { SQLITE, MYSQL }

    private final MineStormAltingSystemPlugin plugin;
    private final Type type;
    private final File sqliteFile;
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
        cfg.setPoolName("MineStormAlt-" + type.name());
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
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException ex) { throw new SQLException("MySQL driver not found", ex); }
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
