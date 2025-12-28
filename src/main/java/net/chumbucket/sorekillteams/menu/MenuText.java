/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.menu;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Placeholder apply + role-based lore filtering.
 * This is the exact same logic you had, just isolated.
 *
 * Now also supports PlaceholderAPI placeholders (if installed + enabled in config).
 */
public final class MenuText {

    private final SorekillTeamsPlugin plugin;

    public MenuText(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> applyList(Player viewer, Team team, List<String> in) {
        List<String> filtered = filterLoreByRole(viewer, team, in);
        List<String> out = new ArrayList<>(filtered.size());
        for (String s : filtered) out.add(apply(viewer, team, s));
        return out;
    }

    public String apply(Player viewer, Team team, String s) {
        if (s == null) return "";
        String out = s;

        String teamName = (team == null || team.getName() == null ? "None" : Msg.color(team.getName()));
        String ownerName = "None";
        String members = (team == null ? "0" : String.valueOf(uniqueMemberCount(team)));

        if (team != null && team.getOwner() != null) ownerName = nameOf(team.getOwner());

        int inviteCount = plugin.teams().getInvites(viewer.getUniqueId()).size();
        String ff = (team == null ? "&7N/A" : (team.isFriendlyFireEnabled() ? "&cENABLED" : "&aDISABLED"));
        String chat = (plugin.teams().isTeamChatEnabled(viewer.getUniqueId()) ? "&aON" : "&cOFF");

        out = out.replace("{team}", teamName);
        out = out.replace("{owner}", ownerName);
        out = out.replace("{members}", members);
        out = out.replace("{invite_count}", String.valueOf(inviteCount));
        out = out.replace("{ff}", Msg.color(ff));
        out = out.replace("{chat}", Msg.color(chat));

        // ✅ Apply external placeholders (PAPI) before color codes
        if (viewer != null && plugin.placeholders() != null) {
            try {
                out = plugin.placeholders().apply(viewer, out);
            } catch (Throwable ignored) {}
        }

        return Msg.color(out);
    }

    /**
     * Filters lore lines that contain "(Team Owner)" or "(Member)" so they only show
     * to the appropriate viewer relative to the given "team" (the placeholder team).
     */
    private List<String> filterLoreByRole(Player viewer, Team team, List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        boolean owner = isOwner(viewer, team);
        boolean member = isMember(viewer, team) && !owner;

        List<String> out = new ArrayList<>(lines.size());
        for (String raw : lines) {
            if (raw == null) continue;

            if (raw.contains("(Team Owner)") && !owner) continue;
            if (raw.contains("(Member)") && !member) continue;

            out.add(raw);
        }
        return out;
    }

    private boolean isOwner(Player viewer, Team team) {
        if (viewer == null || team == null || team.getOwner() == null) return false;
        return viewer.getUniqueId().equals(team.getOwner());
    }

    private boolean isMember(Player viewer, Team team) {
        if (viewer == null || team == null) return false;
        UUID v = viewer.getUniqueId();
        if (team.getOwner() != null && v.equals(team.getOwner())) return true;
        if (team.getMembers() == null) return false;
        return team.getMembers().contains(v);
    }

    public int uniqueMemberCount(Team t) {
        if (t == null) return 0;
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (t.getOwner() != null) set.add(t.getOwner());
        if (t.getMembers() != null) for (UUID u : t.getMembers()) if (u != null) set.add(u);
        return set.size();
    }

    public List<UUID> uniqueTeamMembers(Team team) {
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        if (team == null) return List.of();

        if (team.getOwner() != null) set.add(team.getOwner());
        if (team.getMembers() != null) for (UUID u : team.getMembers()) if (u != null) set.add(u);

        return new ArrayList<>(set);
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "unknown";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null && !off.getName().isBlank()) return off.getName();

        return uuid.toString().substring(0, 8);
    }
}
