package net.chumbucket.sorekillteams.storage.sql;

import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.service.SimpleTeamHomeService;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.storage.TeamHomeStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SqlTeamHomeStorage implements TeamHomeStorage {

    private final SqlDatabase db;
    private final String pfx;

    public SqlTeamHomeStorage(SqlDatabase db) {
        this.db = Objects.requireNonNull(db, "db");
        this.pfx = db.prefix();
    }

    @Override
    public void loadAll(TeamHomeService homes) throws Exception {
        if (!(homes instanceof SimpleTeamHomeService s)) {
            throw new IllegalStateException("SqlTeamHomeStorage requires SimpleTeamHomeService (got " + homes.getClass().getName() + ")");
        }

        s.clearAll();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT team_id, name, display_name, world, x, y, z, yaw, pitch, created_at, created_by, server_name " +
                             "FROM " + pfx + "team_homes"
             );
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                UUID teamId = uuid(rs.getString("team_id"));
                if (teamId == null) continue;

                String name = rs.getString("name");
                String displayName = rs.getString("display_name");
                String world = rs.getString("world");
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                double z = rs.getDouble("z");
                float yaw = rs.getFloat("yaw");
                float pitch = rs.getFloat("pitch");
                long createdAt = rs.getLong("created_at");
                UUID createdBy = uuid(rs.getString("created_by")); // nullable
                String serverName = rs.getString("server_name");

                TeamHome home = new TeamHome(
                        teamId,
                        name,
                        displayName,
                        world,
                        x, y, z,
                        yaw, pitch,
                        createdAt,
                        createdBy,
                        serverName
                );

                s.putLoadedHome(home);
            }
        }
    }

    @Override
    public void saveAll(TeamHomeService homes) throws Exception {
        if (!(homes instanceof SimpleTeamHomeService s)) {
            throw new IllegalStateException("SqlTeamHomeStorage requires SimpleTeamHomeService (got " + homes.getClass().getName() + ")");
        }

        Collection<TeamHome> all = s.allHomes();
        if (all == null) all = List.of();

        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);

            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + pfx + "team_homes")) {
                    del.executeUpdate();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO " + pfx + "team_homes " +
                                "(team_id, name, display_name, world, x, y, z, yaw, pitch, created_at, created_by, server_name) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                )) {
                    for (TeamHome h : all) {
                        if (h == null || h.getTeamId() == null) continue;

                        ins.setString(1, h.getTeamId().toString());
                        ins.setString(2, nvl(h.getName(), ""));
                        ins.setString(3, nvl(h.getDisplayName(), nvl(h.getName(), "")));
                        ins.setString(4, nvl(h.getWorld(), ""));
                        ins.setDouble(5, h.getX());
                        ins.setDouble(6, h.getY());
                        ins.setDouble(7, h.getZ());
                        ins.setFloat(8, h.getYaw());
                        ins.setFloat(9, h.getPitch());
                        ins.setLong(10, h.getCreatedAtMs());

                        UUID createdBy = h.getCreatedBy();
                        if (createdBy == null) ins.setString(11, null);
                        else ins.setString(11, createdBy.toString());

                        ins.setString(12, nvl(h.getServerName(), "default"));

                        ins.addBatch();
                    }
                    ins.executeBatch();
                }

                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    private static String nvl(String s, String def) {
        return (s == null ? def : s);
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
