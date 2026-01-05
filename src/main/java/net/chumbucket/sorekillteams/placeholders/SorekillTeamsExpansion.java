/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.model.TeamHome;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI expansion identifier: sorekillteams
 *
 * Existing:
 * - %sorekillteams_team%
 * - %sorekillteams_team_id%
 * - %sorekillteams_team_owner%
 * - %sorekillteams_team_members%   (total count)
 * - %sorekillteams_in_team%
 *
 * Added (counts):
 * - %sorekillteams_members_total%
 * - %sorekillteams_members_online%
 * - %sorekillteams_members_offline%
 *
 * Added (homes):
 * - %sorekillteams_home_count%
 * - %sorekillteams_home_list%              (comma separated display names)
 * - %sorekillteams_home_<name>%            (display name of a specific home key)
 *
 * Added (team chat toggle):
 * - %sorekillteams_teamchat_mode%          (Enabled/Disabled)
 * - %sorekillteams_teamchat_enabled%       (true/false)
 * 
 * Added (invites toggle):
 * - %sorekillteams_invites_mode%          (Enabled/Disabled)
 * - %sorekillteams_invites_enabled%       (true/false)
 *
 * Notes:
 * - home_<name> key is normalized like TeamHome: lowercase, trim, collapse spaces
 */
public final class SorekillTeamsExpansion extends PlaceholderExpansion {

    private final SorekillTeamsPlugin plugin;

    public SorekillTeamsExpansion(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "sorekillteams"; }
    @Override public String getAuthor() { return "Sorekill"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    /**
     * OfflinePlayer-based request (exists on many PAPI versions).
     */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) return "";
        return resolve(player.getUniqueId(), params);
    }

    /**
     * Player-based request (exists on many PAPI versions).
     */
    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) return "";
        return resolve(player.getUniqueId(), params);
    }

    private String resolve(UUID viewer, String params) {
        if (viewer == null) return "";

        final Team team = plugin.teams().getTeamByPlayer(viewer).orElse(null);
        final String key = params.toLowerCase(Locale.ROOT);

        // ---- basics ----
        return switch (key) {
            case "in_team" -> (team != null) ? "true" : "false";
            case "team" -> (team != null && team.getName() != null) ? team.getName() : "";
            case "team_id" -> (team != null && team.getId() != null) ? team.getId().toString() : "";
            case "team_owner" -> (team != null && team.getOwner() != null) ? safeName(team.getOwner()) : "";
            case "team_members" -> (team != null) ? String.valueOf(uniqueMemberCount(team)) : "0";

            // ✅ team chat toggle placeholders
            case "teamchat_mode" -> {
                boolean on = plugin.teams() != null && plugin.teams().isTeamChatEnabled(viewer);
                yield on ? "Enabled" : "Disabled";
            }
            case "teamchat_enabled" -> {
                boolean on = plugin.teams() != null && plugin.teams().isTeamChatEnabled(viewer);
                yield on ? "true" : "false";
            }

            // ✅ invites toggle placeholders
            case "invites_mode" -> {
                boolean on = plugin.teams() != null && plugin.teams().isInvitesEnabled(viewer);
                yield on ? "Enabled" : "Disabled";
            }
            case "invites_enabled" -> {
                boolean on = plugin.teams() != null && plugin.teams().isInvitesEnabled(viewer);
                yield on ? "true" : "false";
            }

            // ---- counts ----
            case "members_total" -> (team != null) ? String.valueOf(uniqueMemberCount(team)) : "0";
            case "members_online" -> (team != null) ? String.valueOf(countOnline(team)) : "0";
            case "members_offline" -> {
                if (team == null) yield "0";
                int total = uniqueMemberCount(team);
                int on = countOnline(team);
                yield String.valueOf(Math.max(0, total - on));
            }

            // ---- homes ----
            case "home_count" -> String.valueOf(countHomes(team));
            case "home_list" -> listHomes(team);

            default -> {
                if (key.startsWith("home_")) {
                    String raw = params.substring("home_".length());
                    yield homeByKey(team, raw);
                }
                yield "";
            }
        };
    }

    private int countOnline(Team t) {
        if (t == null) return 0;
        int online = 0;
        for (UUID u : uniqueTeamMembers(t)) {
            if (u == null) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) online++;
        }
        return online;
    }

    private int countHomes(Team t) {
        if (t == null) return 0;
        if (plugin.teamHomes() == null) return 0;

        List<TeamHome> homes = plugin.teamHomes().listHomes(t.getId());
        if (homes == null || homes.isEmpty()) return 0;

        int count = 0;
        for (TeamHome h : homes) {
            if (h == null) continue;
            if (h.getName() == null || h.getName().isBlank()) continue;
            count++;
        }
        return count;
    }

    private String listHomes(Team t) {
        if (t == null) return "";
        if (plugin.teamHomes() == null) return "";

        List<TeamHome> homes = plugin.teamHomes().listHomes(t.getId());
        if (homes == null || homes.isEmpty()) return "";

        List<String> out = new ArrayList<>();
        for (TeamHome h : homes) {
            if (h == null) continue;
            String dn = h.getDisplayName();
            if (dn == null || dn.isBlank()) dn = h.getName();
            if (dn == null || dn.isBlank()) continue;
            out.add(dn);
        }

        return String.join(", ", out);
    }

    private String homeByKey(Team t, String rawKey) {
        if (t == null) return "";
        if (plugin.teamHomes() == null) return "";

        String key = normalizeKey(rawKey);
        if (key.isBlank()) return "";

        List<TeamHome> homes = plugin.teamHomes().listHomes(t.getId());
        if (homes == null || homes.isEmpty()) return "";

        for (TeamHome h : homes) {
            if (h == null) continue;
            if (h.getName() == null) continue;

            if (h.getName().equalsIgnoreCase(key)) {
                String dn = h.getDisplayName();
                if (dn == null || dn.isBlank()) dn = h.getName();
                return dn == null ? "" : dn;
            }
        }

        return "";
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    private static int uniqueMemberCount(Team t) {
        if (t == null) return 0;
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (t.getOwner() != null) set.add(t.getOwner());
        if (t.getMembers() != null) for (UUID u : t.getMembers()) if (u != null) set.add(u);
        return set.size();
    }

    private static List<UUID> uniqueTeamMembers(Team t) {
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (t == null) return List.of();
        if (t.getOwner() != null) set.add(t.getOwner());
        if (t.getMembers() != null) for (UUID u : t.getMembers()) if (u != null) set.add(u);
        return new ArrayList<>(set);
    }

    private String safeName(UUID uuid) {
        try {
            OfflinePlayer off = plugin.getServer().getOfflinePlayer(uuid);
            String n = (off != null) ? off.getName() : null;
            return (n == null || n.isBlank()) ? "" : n;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
