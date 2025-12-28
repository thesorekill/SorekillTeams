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

import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import net.chumbucket.sorekillteams.storage.TeamStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public final class SqlTeamStorage implements TeamStorage {

    private final SqlDatabase db;
    private final String pfx;

    public SqlTeamStorage(SqlDatabase db) {
        this.db = Objects.requireNonNull(db, "db");
        this.pfx = db.prefix();
    }

    @Override
    public void loadAll(TeamService service) throws Exception {
        if (!(service instanceof SimpleTeamService s)) {
            throw new IllegalStateException("SqlTeamStorage requires SimpleTeamService (got " + service.getClass().getName() + ")");
        }
        s.putAllTeams(loadAllTeamsSnapshot());
    }

    /**
     * ✅ Snapshot loader (authoritative):
     * Loads the complete teams + members state from SQL and returns it.
     *
     * Used for cross-backend cache invalidation: if a team is disbanded on backend A,
     * backend B can refresh its snapshot and immediately stop showing that team in browse lists.
     */
    public List<Team> loadAllTeamsSnapshot() throws Exception {
        List<Team> loaded = new ArrayList<>();

        try (Connection c = db.getConnection()) {

            Map<UUID, List<UUID>> membersByTeam = new HashMap<>();

            // members first
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT team_id, member_uuid FROM " + pfx + "team_members"
            );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    UUID teamId = uuid(rs.getString("team_id"));
                    UUID member = uuid(rs.getString("member_uuid"));
                    if (teamId == null || member == null) continue;

                    membersByTeam.computeIfAbsent(teamId, __ -> new ArrayList<>()).add(member);
                }
            }

            // teams
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, owner_uuid, friendly_fire, created_at FROM " + pfx + "teams"
            );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    UUID id = uuid(rs.getString("id"));
                    UUID owner = uuid(rs.getString("owner_uuid"));
                    if (id == null || owner == null) continue;

                    String name = rs.getString("name");
                    long createdAt = rs.getLong("created_at");
                    boolean ff = rs.getBoolean("friendly_fire");

                    Team t = new Team(id, name, owner, createdAt);
                    t.setFriendlyFireEnabled(ff);

                    // deterministic ordering
                    List<UUID> ms = membersByTeam.getOrDefault(id, List.of());
                    ms.stream()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(UUID::toString))
                            .forEach(u -> t.getMembers().add(u));

                    // owner invariant
                    if (!t.getMembers().contains(owner)) {
                        t.getMembers().add(owner);
                    }

                    loaded.add(t);
                }
            }
        }

        return loaded;
    }

    @Override
    public void saveAll(TeamService service) throws Exception {
        if (!(service instanceof SimpleTeamService s)) {
            throw new IllegalStateException("SqlTeamStorage requires SimpleTeamService (got " + service.getClass().getName() + ")");
        }

        Collection<Team> teams = s.allTeams();
        if (teams == null) teams = List.of();

        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);

            try {
                // wipe & rewrite (simple + safe; you can optimize later)
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + pfx + "team_members")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + pfx + "teams")) {
                    ps.executeUpdate();
                }

                // insert teams
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + pfx + "teams (id, name, owner_uuid, friendly_fire, created_at) VALUES (?,?,?,?,?)"
                )) {
                    for (Team t : teams) {
                        if (t == null || t.getId() == null || t.getOwner() == null) continue;

                        ps.setString(1, t.getId().toString());
                        ps.setString(2, t.getName() == null ? "Team" : t.getName());
                        ps.setString(3, t.getOwner().toString());
                        ps.setBoolean(4, t.isFriendlyFireEnabled());
                        ps.setLong(5, t.getCreatedAtMs());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // insert members
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + pfx + "team_members (team_id, member_uuid) VALUES (?,?)"
                )) {
                    for (Team t : teams) {
                        if (t == null || t.getId() == null || t.getOwner() == null) continue;

                        UUID teamId = t.getId();

                        LinkedHashSet<UUID> members = new LinkedHashSet<>();
                        for (UUID m : t.getMembers()) if (m != null) members.add(m);
                        members.add(t.getOwner());

                        for (UUID m : members) {
                            ps.setString(1, teamId.toString());
                            ps.setString(2, m.toString());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
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

    // =========================================================
    // OPTION 3 SUPPORT: On-demand SQL backfill helpers
    // =========================================================

    public UUID findTeamIdForMember(UUID memberUuid) throws Exception {
        if (memberUuid == null) return null;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT team_id FROM " + pfx + "team_members WHERE member_uuid = ?"
             )) {

            ps.setString(1, memberUuid.toString());
            try { ps.setFetchSize(1); } catch (Exception ignored) {}

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return uuid(rs.getString("team_id"));
            }
        }
    }

    public Team loadTeamById(UUID teamId) throws Exception {
        if (teamId == null) return null;

        try (Connection c = db.getConnection()) {

            Team t;

            // team row
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, name, owner_uuid, friendly_fire, created_at FROM " + pfx + "teams WHERE id = ?"
            )) {
                ps.setString(1, teamId.toString());

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;

                    UUID id = uuid(rs.getString("id"));
                    UUID owner = uuid(rs.getString("owner_uuid"));
                    if (id == null || owner == null) return null;

                    String name = rs.getString("name");
                    long createdAt = rs.getLong("created_at");
                    boolean ff = rs.getBoolean("friendly_fire");

                    t = new Team(id, name, owner, createdAt);
                    t.setFriendlyFireEnabled(ff);

                    if (!t.getMembers().contains(owner)) {
                        t.getMembers().add(owner);
                    }
                }
            }

            // members
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT member_uuid FROM " + pfx + "team_members WHERE team_id = ?"
            )) {
                ps.setString(1, teamId.toString());

                try (ResultSet rs = ps.executeQuery()) {
                    List<UUID> members = new ArrayList<>();
                    while (rs.next()) {
                        UUID m = uuid(rs.getString("member_uuid"));
                        if (m != null) members.add(m);
                    }

                    members.stream()
                            .sorted(Comparator.comparing(UUID::toString))
                            .forEach(u -> {
                                if (!t.getMembers().contains(u)) {
                                    t.getMembers().add(u);
                                }
                            });
                }
            }

            UUID owner = t.getOwner();
            if (owner != null && !t.getMembers().contains(owner)) {
                t.getMembers().add(owner);
            }

            return t;
        }
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
