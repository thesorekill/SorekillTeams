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

import net.chumbucket.sorekillteams.model.TeamInvite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SqlTeamInviteStorage {

    private final SqlDatabase db;

    public SqlTeamInviteStorage(SqlDatabase db) {
        this.db = db;
    }

    private String table() {
        return db.prefix() + "invites";
    }

    /**
     * Create table if it doesn't exist.
     * Call this from SqlMigrations.migrate(...)
     */
    public void ensureSchema(Connection c) throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS " + table() + " (" +
                "invitee_uuid VARCHAR(36) NOT NULL," +
                "team_id VARCHAR(36) NOT NULL," +
                "inviter_uuid VARCHAR(36) NOT NULL," +
                "created_at_ms BIGINT NOT NULL," +
                "expires_at_ms BIGINT NOT NULL," +
                "PRIMARY KEY (invitee_uuid, team_id)" +
                ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }

        // Best-effort indexes; if a dialect rejects IF NOT EXISTS, it won't kill the plugin
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE INDEX IF NOT EXISTS " + table() + "_invitee_expires_idx ON " + table() + " (invitee_uuid, expires_at_ms)"
        )) { ps.execute(); } catch (Exception ignored) {}

        try (PreparedStatement ps = c.prepareStatement(
                "CREATE INDEX IF NOT EXISTS " + table() + "_team_expires_idx ON " + table() + " (team_id, expires_at_ms)"
        )) { ps.execute(); } catch (Exception ignored) {}
    }

    public int purgeExpired(long nowMs) throws Exception {
        String sql = "DELETE FROM " + table() + " WHERE expires_at_ms <= ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            return ps.executeUpdate();
        }
    }

    public int pendingForTarget(UUID invitee, long nowMs) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE invitee_uuid=? AND expires_at_ms > ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, invitee.toString());
            ps.setLong(2, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int outgoingForTeam(UUID teamId, long nowMs) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + table() + " WHERE team_id=? AND expires_at_ms > ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, teamId.toString());
            ps.setLong(2, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean hasInviteFromOtherTeam(UUID invitee, UUID teamId, long nowMs) throws Exception {
        String sql = "SELECT team_id FROM " + table() + " WHERE invitee_uuid=? AND expires_at_ms > ? LIMIT 5";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, invitee.toString());
            ps.setLong(2, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tid = rs.getString(1);
                    if (tid == null) continue;
                    if (teamId == null) return true;
                    if (!teamId.toString().equalsIgnoreCase(tid)) return true;
                }
            }
        }
        return false;
    }

    public TeamInvite find(UUID invitee, UUID teamId, long nowMs, String teamNameFallback) throws Exception {
        String sql = "SELECT inviter_uuid, created_at_ms, expires_at_ms FROM " + table() +
                " WHERE invitee_uuid=? AND team_id=? AND expires_at_ms > ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, invitee.toString());
            ps.setString(2, teamId.toString());
            ps.setLong(3, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                UUID inviter = UUID.fromString(rs.getString(1));
                long createdAt = rs.getLong(2);
                long expiresAt = rs.getLong(3);
                return new TeamInvite(teamId, teamNameFallback, inviter, invitee, createdAt, expiresAt);
            }
        }
    }

    public List<TeamInvite> listActive(UUID invitee, long nowMs, java.util.function.Function<UUID, String> teamNameResolver) throws Exception {
        List<TeamInvite> out = new ArrayList<>();

        String sql = "SELECT team_id, inviter_uuid, created_at_ms, expires_at_ms FROM " + table() +
                " WHERE invitee_uuid=? AND expires_at_ms > ? ORDER BY expires_at_ms ASC";

        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, invitee.toString());
            ps.setLong(2, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID teamId = UUID.fromString(rs.getString(1));
                    UUID inviter = UUID.fromString(rs.getString(2));
                    long createdAt = rs.getLong(3);
                    long expiresAt = rs.getLong(4);

                    String teamName = "Team";
                    try {
                        if (teamNameResolver != null && teamId != null) {
                            String n = teamNameResolver.apply(teamId);
                            if (n != null && !n.isBlank()) teamName = n;
                        }
                    } catch (Exception ignored) {}

                    out.add(new TeamInvite(teamId, teamName, inviter, invitee, createdAt, expiresAt));
                }
            }
        }

        return out;
    }

    /**
     * Insert or refresh existing row for (invitee, team_id).
     * Cross-dialect safe approach: UPDATE then INSERT if needed.
     */
    public void upsert(UUID teamId, UUID inviter, UUID invitee, long createdAtMs, long expiresAtMs) throws Exception {
        String upd = "UPDATE " + table() + " SET inviter_uuid=?, created_at_ms=?, expires_at_ms=? WHERE invitee_uuid=? AND team_id=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(upd)) {
            ps.setString(1, inviter.toString());
            ps.setLong(2, createdAtMs);
            ps.setLong(3, expiresAtMs);
            ps.setString(4, invitee.toString());
            ps.setString(5, teamId.toString());
            int n = ps.executeUpdate();
            if (n > 0) return;
        }

        String ins = "INSERT INTO " + table() + " (invitee_uuid, team_id, inviter_uuid, created_at_ms, expires_at_ms) VALUES (?,?,?,?,?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(ins)) {
            ps.setString(1, invitee.toString());
            ps.setString(2, teamId.toString());
            ps.setString(3, inviter.toString());
            ps.setLong(4, createdAtMs);
            ps.setLong(5, expiresAtMs);
            ps.executeUpdate();
        }
    }

    public boolean delete(UUID invitee, UUID teamId) throws Exception {
        String del = "DELETE FROM " + table() + " WHERE invitee_uuid=? AND team_id=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(del)) {
            ps.setString(1, invitee.toString());
            ps.setString(2, teamId.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAllForTeam(UUID teamId) throws Exception {
        String del = "DELETE FROM " + table() + " WHERE team_id=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(del)) {
            ps.setString(1, teamId.toString());
            ps.executeUpdate();
        }
    }
}
