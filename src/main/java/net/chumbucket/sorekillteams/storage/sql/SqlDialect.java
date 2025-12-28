package net.chumbucket.sorekillteams.storage.sql;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.Locale;

public enum SqlDialect {
    MYSQL,
    MARIADB,
    POSTGRESQL,
    SQLITE,
    H2;

    public static SqlDialect fromStorageType(String type) {
        if (type == null) return MYSQL;
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "postgresql", "postgres", "pg" -> POSTGRESQL;
            case "sqlite" -> SQLITE;
            case "h2" -> H2;
            default -> MYSQL;
        };
    }

    /** Explicit driver class name so shaded jars don't rely on ServiceLoader behavior. */
    public String driverClassName() {
        return switch (this) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
            case SQLITE -> "org.sqlite.JDBC";
            case H2 -> "org.h2.Driver";
        };
    }

    public String buildJdbcUrl(SorekillTeamsPlugin plugin, ConfigurationSection sql) {
        String host = sql.getString("host", "127.0.0.1");
        int port = sql.getInt("port", 0);
        String db = sql.getString("database", "sorekillteams");
        boolean ssl = sql.getBoolean("use_ssl", false);

        String params = sql.getString("parameters", "");

        String file = sql.getString("file", "sorekillteams.db");
        String h2Mode = sql.getString("h2_mode", "file");
        String h2Compat = sql.getString("h2_compatibility_mode", "MYSQL");

        return switch (this) {
            case MYSQL -> {
                if (port == 0) port = 3306;
                yield "jdbc:mysql://" + host + ":" + port + "/" + db +
                        "?useSSL=" + ssl + (params.isBlank() ? "" : "&" + params);
            }
            case MARIADB -> {
                if (port == 0) port = 3306;
                yield "jdbc:mariadb://" + host + ":" + port + "/" + db +
                        "?useSSL=" + ssl + (params.isBlank() ? "" : "&" + params);
            }
            case POSTGRESQL -> {
                if (port == 0) port = 5432;
                yield "jdbc:postgresql://" + host + ":" + port + "/" + db +
                        "?ssl=" + ssl + (params.isBlank() ? "" : "&" + params);
            }
            case SQLITE -> {
                File f = SqlDatabase.resolveDataFile(plugin, file);
                yield "jdbc:sqlite:" + f.getAbsolutePath();
            }
            case H2 -> {
                if ("memory".equalsIgnoreCase(h2Mode)) {
                    yield "jdbc:h2:mem:" + db + ";MODE=" + h2Compat + ";DB_CLOSE_DELAY=-1";
                }
                File f = SqlDatabase.resolveDataFile(plugin, file);
                yield "jdbc:h2:file:" + f.getAbsolutePath() + ";MODE=" + h2Compat;
            }
        };
    }
}
