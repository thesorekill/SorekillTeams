/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.storage;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.service.TeamService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public final class YamlTeamStorage implements TeamStorage {

    private static final String ROOT_TEAMS = "teams";
    private static final String KEY_NAME = "name";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_MEMBERS = "members";
    private static final String KEY_FRIENDLY_FIRE = "friendly_fire";
    private static final String KEY_CREATED_AT = "created_at";

    private final SorekillTeamsPlugin plugin;
    private final File file;

    public YamlTeamStorage(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
    }

    @Override
    public void loadAll(TeamService service) {
        if (!(service instanceof SimpleTeamService simple)) {
            plugin.getLogger().warning("Storage load skipped: unsupported TeamService type (" +
                    (service == null ? "null" : service.getClass().getName()) + ")");
            return;
        }

        // If missing, just start fresh (no warning needed)
        if (!file.exists()) {
            plugin.getLogger().info("No teams.yml found; starting fresh.");
            return;
        }

        final YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection root = yml.getConfigurationSection(ROOT_TEAMS);
        if (root == null) {
            plugin.getLogger().info("teams.yml has no '" + ROOT_TEAMS + "' section; starting fresh.");
            return;
        }

        int loaded = 0;
        int skipped = 0;

        for (String teamKey : root.getKeys(false)) {
            final ConfigurationSection tSec = root.getConfigurationSection(teamKey);
            if (tSec == null) {
                skipped++;
                continue;
            }

            try {
                final UUID id = safeUuid(teamKey);
                if (id == null) {
                    plugin.getLogger().warning("Skipping team with invalid UUID key: " + teamKey);
                    skipped++;
                    continue;
                }

                final String name = normalizeName(tSec.getString(KEY_NAME, "Team"));

                final UUID owner = safeUuid(tSec.getString(KEY_OWNER, null));
                if (owner == null) {
                    plugin.getLogger().warning("Skipping team " + id + " ('" + name + "') due to missing/invalid owner UUID");
                    skipped++;
                    continue;
                }

                // Members (unique + owner included)
                final LinkedHashSet<UUID> members = new LinkedHashSet<>();
                for (String ms : tSec.getStringList(KEY_MEMBERS)) {
                    UUID m = safeUuid(ms);
                    if (m != null) members.add(m);
                }
                members.add(owner);

                // created date (fallback: now if missing/invalid)
                long createdAt = tSec.getLong(KEY_CREATED_AT, System.currentTimeMillis());
                if (createdAt <= 0) createdAt = System.currentTimeMillis();

                final Team t = new Team(id, name, owner, createdAt);

                // Replace internal members set with our cleaned set
                t.getMembers().clear();
                t.getMembers().addAll(members);

                // per-team friendly fire
                t.setFriendlyFireEnabled(tSec.getBoolean(KEY_FRIENDLY_FIRE, false));

                simple.putLoadedTeam(t);
                loaded++;
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping malformed team entry '" + teamKey + "': " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                skipped++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " teams. Skipped " + skipped + " malformed entries.");
    }

    @Override
    public void saveAll(TeamService service) {
        if (!(service instanceof SimpleTeamService simple)) {
            plugin.getLogger().warning("Storage save skipped: unsupported TeamService type (" +
                    (service == null ? "null" : service.getClass().getName()) + ")");
            return;
        }

        if (!ensureDataFolder()) {
            plugin.getLogger().severe("Failed to create plugin data folder: " + plugin.getDataFolder().getAbsolutePath());
            return;
        }

        // Snapshot first to avoid concurrent modification during async autosave
        final List<TeamSnapshot> snapshot = snapshotTeams(simple);

        final YamlConfiguration yml = new YamlConfiguration();

        for (TeamSnapshot t : snapshot) {
            final String base = ROOT_TEAMS + "." + t.id;

            yml.set(base + "." + KEY_NAME, t.name);
            yml.set(base + "." + KEY_OWNER, t.owner.toString());

            // Ensure members list is unique + includes owner
            final LinkedHashSet<UUID> members = new LinkedHashSet<>(t.members);
            members.add(t.owner);

            yml.set(base + "." + KEY_MEMBERS,
                    members.stream().map(UUID::toString).collect(Collectors.toList()));

            yml.set(base + "." + KEY_FRIENDLY_FIRE, t.friendlyFire);
            yml.set(base + "." + KEY_CREATED_AT, t.createdAtMs);
        }

        final File tmp = new File(plugin.getDataFolder(), "teams.yml.tmp");

        try {
            yml.save(tmp);

            Path tmpPath = tmp.toPath();
            Path destPath = file.toPath();

            try {
                Files.move(tmpPath, destPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmpPath, destPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save teams.yml: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean ensureDataFolder() {
        File folder = plugin.getDataFolder();
        return folder.exists() || folder.mkdirs();
    }

    private List<TeamSnapshot> snapshotTeams(SimpleTeamService simple) {
        final List<Team> teams = new ArrayList<>(simple.allTeams());
        teams.removeIf(Objects::isNull);
        teams.sort(Comparator.comparing(t -> t.getId().toString()));

        final List<TeamSnapshot> out = new ArrayList<>(teams.size());

        for (Team t : teams) {
            if (t.getId() == null || t.getOwner() == null) continue;

            final UUID id = t.getId();
            final String name = normalizeName(t.getName());
            final UUID owner = t.getOwner();
            final long created = t.getCreatedAtMs();
            final boolean ff = t.isFriendlyFireEnabled();

            Set<UUID> membersCopy;
            try {
                membersCopy = new LinkedHashSet<>(t.getMembers());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to snapshot members for team " + id + ": " +
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
                membersCopy = new LinkedHashSet<>();
            }

            out.add(new TeamSnapshot(id, name, owner, membersCopy, ff, created));
        }

        return out;
    }

    private record TeamSnapshot(
            UUID id,
            String name,
            UUID owner,
            Set<UUID> members,
            boolean friendlyFire,
            long createdAtMs
    ) {}

    private static UUID safeUuid(String s) {
        if (s == null) return null;
        final String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String normalizeName(String nameRaw) {
        if (nameRaw == null) return "Team";
        String n = nameRaw.trim().replaceAll("\\s{2,}", " ");
        return n.isBlank() ? "Team" : n;
    }
}
