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

import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.service.SimpleTeamHomeService;
import net.chumbucket.sorekillteams.service.TeamHomeService;
import net.chumbucket.sorekillteams.storage.TeamHomeStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

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

        // Clear first; if load succeeds we mark clean below.
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
                UUID createdBy = uuid(rs.getString("created_by"));
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

        // Loaded snapshot is authoritative; don't treat it as dirty.
        s.markClean();
    }

    @Override
    public void saveAll(TeamHomeService homes) throws Exception {
        if (!(homes instanceof SimpleTeamHomeService s)) {
            throw new IllegalStateException("SqlTeamHomeStorage requires SimpleTeamHomeService (got " + homes.getClass().getName() + ")");
        }

        // Hardening #1: no-op if nothing changed.
        if (!s.isDirty()) return;

        // Snapshot from memory
        Collection<TeamHome> all = s.allHomes();
        if (all == null) all = List.of();

        // Build per-team view (for safe pruning)
        Map<UUID, Map<String, TeamHome>> byTeam = new HashMap<>();
        for (TeamHome h : all) {
            if (h == null || h.getTeamId() == null) continue;
            String key = nvl(h.getName(), "").trim();
            if (key.isBlank()) continue;

            byTeam.computeIfAbsent(h.getTeamId(), __ -> new HashMap<>())
                    .put(key, h);
        }

        boolean snapshotEmpty = byTeam.isEmpty();

        // Hardening #2: prevent "empty snapshot wipes DB" unless explicitly allowed.
        // If empty and not allowed, we simply do nothing (no deletes, no upserts).
        if (snapshotEmpty && !s.consumeAllowEmptyWriteOnce()) {
            return;
        }

        final SqlDialect dialect = db.dialect();

        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);

            try {
                // -------------------------
                // 1) Upsert each home row
                // -------------------------
                final String upsertSql = upsertSql(dialect);

                try (PreparedStatement up = c.prepareStatement(upsertSql)) {
                    for (Map<String, TeamHome> teamMap : byTeam.values()) {
                        for (TeamHome h : teamMap.values()) {
                            bindUpsertParams(dialect, up, h);
                            up.addBatch();
                        }
                    }
                    up.executeBatch();
                }

                // -------------------------
                // 2) Prune stale homes PER TEAM
                // -------------------------
                if (snapshotEmpty) {
                    try (PreparedStatement delAll = c.prepareStatement("DELETE FROM " + pfx + "team_homes")) {
                        delAll.executeUpdate();
                    }
                } else {
                    for (var e : byTeam.entrySet()) {
                        UUID teamId = e.getKey();
                        Set<String> keepNames = e.getValue().keySet();
                        pruneTeam(c, teamId, keepNames);
                    }
                }

                c.commit();
                s.markClean();
            } catch (Exception ex) {
                try { c.rollback(); } catch (Exception ignored) {}
                throw ex;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * ✅ Called when a team is disbanded.
     * Removes all homes belonging to that team from SQL.
     */
    @Override
    public void deleteTeam(UUID teamId) throws Exception {
        if (teamId == null) return;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + pfx + "team_homes WHERE team_id=?"
             )) {
            ps.setString(1, teamId.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------
    // Hardening helpers
    // ------------------------------------------------------------

    private void pruneTeam(Connection c, UUID teamId, Set<String> keepNames) throws Exception {
        if (teamId == null) return;

        if (keepNames == null || keepNames.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + pfx + "team_homes WHERE team_id=?"
            )) {
                ps.setString(1, teamId.toString());
                ps.executeUpdate();
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ").append(pfx).append("team_homes WHERE team_id=? AND name NOT IN (");
        int n = keepNames.size();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");

        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int idx = 1;
            ps.setString(idx++, teamId.toString());
            for (String name : keepNames) {
                ps.setString(idx++, name);
            }
            ps.executeUpdate();
        }
    }

    private String upsertSql(SqlDialect d) {
        String table = pfx + "team_homes";

        return switch (d) {
            case POSTGRESQL, SQLITE -> (
                    "INSERT INTO " + table + " " +
                            "(team_id, name, display_name, world, x, y, z, yaw, pitch, created_at, created_by, server_name) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(team_id, name) DO UPDATE SET " +
                            "display_name=excluded.display_name, " +
                            "world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, " +
                            "yaw=excluded.yaw, pitch=excluded.pitch, " +
                            "created_at=excluded.created_at, created_by=excluded.created_by, " +
                            "server_name=excluded.server_name"
            );

            case MYSQL, MARIADB -> (
                    "INSERT INTO " + table + " " +
                            "(team_id, name, display_name, world, x, y, z, yaw, pitch, created_at, created_by, server_name) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "display_name=VALUES(display_name), " +
                            "world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), " +
                            "yaw=VALUES(yaw), pitch=VALUES(pitch), " +
                            "created_at=VALUES(created_at), created_by=VALUES(created_by), " +
                            "server_name=VALUES(server_name)"
            );

            case H2 -> (
                    "MERGE INTO " + table + " " +
                            "(team_id, name, display_name, world, x, y, z, yaw, pitch, created_at, created_by, server_name) " +
                            "KEY (team_id, name) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            );
        };
    }

    private void bindUpsertParams(SqlDialect d, PreparedStatement ps, TeamHome h) throws Exception {
        ps.setString(1, h.getTeamId().toString());
        ps.setString(2, nvl(h.getName(), ""));
        ps.setString(3, nvl(h.getDisplayName(), nvl(h.getName(), "")));
        ps.setString(4, nvl(h.getWorld(), ""));
        ps.setDouble(5, h.getX());
        ps.setDouble(6, h.getY());
        ps.setDouble(7, h.getZ());
        ps.setFloat(8, h.getYaw());
        ps.setFloat(9, h.getPitch());
        ps.setLong(10, h.getCreatedAtMs());

        UUID createdBy = h.getCreatedBy();
        if (createdBy == null) ps.setString(11, null);
        else ps.setString(11, createdBy.toString());

        ps.setString(12, nvl(h.getServerName(), "default"));
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
