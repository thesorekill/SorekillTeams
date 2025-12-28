/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public final class SqlDatabase {

    private final SorekillTeamsPlugin plugin;
    private final SqlDialect dialect;
    private final String prefix;

    private HikariDataSource ds;

    public SqlDatabase(SorekillTeamsPlugin plugin, SqlDialect dialect, String prefix) {
        this.plugin = plugin;
        this.dialect = dialect;
        this.prefix = (prefix == null || prefix.isBlank()) ? "st_" : prefix.trim();
    }

    public void start(ConfigurationSection sql) {
        if (sql == null) throw new IllegalArgumentException("storage.sql section missing");

        // Ensure plugin folder exists (important for sqlite/h2 file mode)
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + plugin.getDataFolder().getAbsolutePath());
        }

        HikariConfig cfg = new HikariConfig();

        // pool settings
        ConfigurationSection pool = sql.getConfigurationSection("pool");
        if (pool != null) {
            cfg.setMaximumPoolSize(Math.max(1, pool.getInt("maximum_pool_size", 10)));
            cfg.setMinimumIdle(Math.max(0, pool.getInt("minimum_idle", 2)));
            cfg.setConnectionTimeout(Math.max(250L, pool.getLong("connection_timeout_ms", 10000)));
            cfg.setIdleTimeout(Math.max(1000L, pool.getLong("idle_timeout_ms", 600000)));
            cfg.setMaxLifetime(Math.max(1000L, pool.getLong("max_lifetime_ms", 1800000)));
        }

        cfg.setPoolName("SorekillTeams");

        String jdbcUrl = dialect.buildJdbcUrl(plugin, sql);
        cfg.setJdbcUrl(jdbcUrl);

        // IMPORTANT: don’t rely on ServiceLoader inside shaded jars
        cfg.setDriverClassName(dialect.driverClassName());

        String user = sql.getString("username", "");
        String pass = sql.getString("password", "");

        // sqlite/h2 commonly don’t require credentials
        if (user != null && !user.isBlank()) cfg.setUsername(user);
        if (pass != null && !pass.isBlank()) cfg.setPassword(pass);

        // safe-ish defaults (mainly relevant for mysql/mariadb)
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // If you ever want: cfg.setLeakDetectionThreshold(...)

        // Build datasource
        this.ds = new HikariDataSource(cfg);

        // migrations
        try (Connection c = getConnection()) {
            SqlMigrations.migrate(c, prefix);
        } catch (SQLException e) {
            // If migrations fail, close the pool so we don’t leak threads
            stop();
            throw new RuntimeException("Failed to run SQL migrations", e);
        }
    }

    public Connection getConnection() throws SQLException {
        if (ds == null) throw new IllegalStateException("SQL datasource not started");
        return ds.getConnection();
    }

    public String prefix() {
        return prefix;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public void stop() {
        if (ds != null) {
            try { ds.close(); } catch (Exception ignored) {}
            ds = null;
        }
    }

    public static File resolveDataFile(SorekillTeamsPlugin plugin, String relativePath) {
        File folder = plugin.getDataFolder();
        File f = new File(folder, (relativePath == null || relativePath.isBlank()) ? "sorekillteams.db" : relativePath);

        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        return f;
    }
}
