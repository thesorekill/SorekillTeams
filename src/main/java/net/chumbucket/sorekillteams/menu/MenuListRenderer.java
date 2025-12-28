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
import net.chumbucket.sorekillteams.model.TeamHome;
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.service.SimpleTeamService;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Dynamic lists + pagination + team_info home bed rendering.
 */
public final class MenuListRenderer {

    private final SorekillTeamsPlugin plugin;
    private final MenuText text;
    private final MenuSkullFactory skulls;
    private final MenuCycler cycler;

    public MenuListRenderer(SorekillTeamsPlugin plugin, MenuText text, MenuSkullFactory skulls, MenuCycler cycler) {
        this.plugin = plugin;
        this.text = text;
        this.skulls = skulls;
        this.cycler = cycler;
    }

    public void renderDynamicList(String menuKey, Inventory inv, MenuHolder holder, int size, Player viewer, Team viewerTeam,
                                  ConfigurationSection list) {

        String type = plugin.menus().str(list, "type", "").toUpperCase(Locale.ROOT);
        List<Integer> slots = list.getIntegerList("slots");
        if (slots == null) slots = List.of();

        int perPage = Math.max(1, slots.size());
        ConfigurationSection menu = plugin.menus().menu(menuKey);
        if (menu != null) {
            ConfigurationSection pag = menu.getConfigurationSection("pagination");
            if (pag != null && plugin.menus().bool(pag, "enabled", false)) {
                perPage = Math.max(1, plugin.menus().integer(pag, "per_page", perPage));
            }
        }

        int page = Math.max(0, holder.page());
        int start = page * perPage;

        ConfigurationSection itemSec = list.getConfigurationSection("item");
        Material mat = plugin.menus().material(itemSec, "material", Material.PAPER);
        String name = plugin.menus().str(itemSec, "name", "&fItem");
        List<String> lore = plugin.menus().strList(itemSec, "lore");

        boolean closeOnClick = plugin.menus().bool(list, "close_on_click", false);
        String clickActionTemplate = plugin.menus().str(list, "click", "NONE");

        if (type.equals("INVITES")) {
            List<TeamInvite> invs = new ArrayList<>(plugin.teams().getInvites(viewer.getUniqueId()));
            invs.sort(Comparator.comparingLong(TeamInvite::getExpiresAtMs));

            List<TeamInvite> pageItems = slice(invs, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                TeamInvite invt = pageItems.get(i);

                String inviteTeam = plugin.teams().getTeamById(invt.getTeamId())
                        .map(Team::getName)
                        .orElse(invt.getTeamName() == null ? "Team" : invt.getTeamName());

                String inviteOwner = text.nameOf(invt.getInviter());
                String expires = formatExpires(invt.getExpiresAtMs());

                String builtName = Msg.color(name.replace("{invite_team}", Msg.color(inviteTeam)));

                List<String> builtLore = new ArrayList<>();
                for (String line : lore) {
                    builtLore.add(Msg.color(
                            line.replace("{invite_team}", Msg.color(inviteTeam))
                                    .replace("{invite_owner}", inviteOwner)
                                    .replace("{invite_expires}", expires)
                    ));
                }

                inv.setItem(slot, MenuItems.item(mat, builtName, builtLore));

                String action = clickActionTemplate
                        .replace("{invite_team_id}", invt.getTeamId().toString())
                        .replace("{invite_team}", inviteTeam);

                holder.bind(slot, action, closeOnClick);
            }
            return;
        }

        if (type.equals("TEAMS")) {

            // ✅ IMPORTANT: make browse teams network-safe.
            // Refresh the global SQL snapshot (TTL guarded, async DB work) before reading cache.
            // This prevents “phantom teams” after disband on another backend.
            try {
                plugin.ensureTeamsSnapshotFreshFromSql();
            } catch (Throwable ignored) {
                // never break menu rendering
            }

            List<Team> teams = new ArrayList<>();
            if (plugin.teams() instanceof SimpleTeamService sts) teams.addAll(sts.allTeams());
            teams.sort(Comparator.comparing(t -> (t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT))));

            List<Team> pageItems = slice(teams, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                Team t = pageItems.get(i);

                String owner = text.nameOf(t.getOwner());
                String membersCount = String.valueOf(MenuTeams.uniqueMemberCount(t));

                holder.bind(slot, "OPEN:team_members:" + t.getId(), closeOnClick);

                if (mat == Material.PLAYER_HEAD) {
                    inv.setItem(slot, skulls.buildBrowseTeamCycleHead(viewer, t, 0, name, lore, owner, membersCount));
                } else {
                    String teamName = (t.getName() == null ? "Team" : t.getName());
                    ItemStack it = MenuItems.item(
                            mat,
                            Msg.color(name.replace("{team}", Msg.color(teamName))),
                            lore.stream()
                                    .map(x -> Msg.color(x.replace("{team}", Msg.color(teamName))
                                            .replace("{owner}", owner)
                                            .replace("{members}", membersCount)))
                                    .toList()
                    );
                    inv.setItem(slot, it);
                }
            }

            if (mat == Material.PLAYER_HEAD && !pageItems.isEmpty() && !slots.isEmpty()) {
                cycler.startBrowseTeamsCycling(
                        viewer,
                        holder,
                        slots,
                        pageItems,
                        size,
                        name,
                        lore,
                        text::nameOf,
                        MenuTeams::uniqueMemberCount,
                        MenuTeams::uniqueTeamMembers
                );
            }
            return;
        }

        if (type.equals("TEAM_MEMBERS")) {
            Team targetTeam = viewerTeam;

            String ctxTeamId = holder.ctxGet("team_id");
            if (ctxTeamId != null && !ctxTeamId.isBlank()) {
                UUID tid = MenuIds.safeUuid(ctxTeamId);
                if (tid != null) targetTeam = plugin.teams().getTeamById(tid).orElse(null);
            }
            if (targetTeam == null) return;

            List<UUID> members = MenuTeams.uniqueTeamMembers(targetTeam);
            List<UUID> pageItems = slice(members, start, perPage);

            boolean isOwner = targetTeam.getOwner() != null && targetTeam.getOwner().equals(viewer.getUniqueId());

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                UUID u = pageItems.get(i);

                String memberName = text.nameOf(u);
                String role = (targetTeam.getOwner() != null && targetTeam.getOwner().equals(u)) ? "Owner" : "Member";

                String action = "NONE";
                if (isOwner && targetTeam.getOwner() != null && !targetTeam.getOwner().equals(u) && !viewer.getUniqueId().equals(u)) {
                    action = clickActionTemplate.replace("{member_uuid}", u.toString()).replace("{member_name}", memberName);
                }

                final Team teamForLore = targetTeam;
                final String memberNameFinal = memberName;
                final String roleFinal = role;
                final int slotFinal = slot;
                final UUID uuidFinal = u;

                Runnable refresh = () -> {
                    if (!viewer.isOnline()) return;

                    if (viewer.getOpenInventory() == null) return;
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top == null) return;

                    if (!(top.getHolder() instanceof MenuHolder h)) return;
                    if (h != holder) return;
                    if (!"team_members".equalsIgnoreCase(h.menuKey())) return;

                    ItemStack rebuilt = skulls.buildPlayerHead(
                            uuidFinal,
                            Msg.color(name.replace("{member_name}", memberNameFinal).replace("{member_role}", roleFinal)),
                            text.applyList(viewer, teamForLore, lore).stream()
                                    .map(x -> x.replace("{member_name}", memberNameFinal).replace("{member_role}", roleFinal))
                                    .toList(),
                            null
                    );
                    top.setItem(slotFinal, rebuilt);
                };

                ItemStack head = skulls.buildPlayerHead(
                        u,
                        Msg.color(name.replace("{member_name}", memberName).replace("{member_role}", role)),
                        text.applyList(viewer, targetTeam, lore).stream()
                                .map(x -> x.replace("{member_name}", memberName).replace("{member_role}", role))
                                .toList(),
                        refresh
                );

                inv.setItem(slot, head);
                holder.bind(slot, action, closeOnClick);
            }
            return;
        }

        if (type.equals("TEAM_HOMES")) {
            if (viewerTeam == null) return;

            List<String> homes = getTeamHomeNames(viewerTeam);
            List<String> pageItems = slice(homes, start, perPage);

            for (int i = 0; i < Math.min(slots.size(), pageItems.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), size);
                String homeName = pageItems.get(i);

                ItemStack it = MenuItems.item(
                        mat,
                        Msg.color(name.replace("{home_name}", homeName)),
                        lore.stream().map(x -> Msg.color(x.replace("{home_name}", homeName))).toList()
                );
                inv.setItem(slot, it);

                String action = clickActionTemplate.replace("{home_name}", homeName);
                holder.bind(slot, action, closeOnClick);
            }
        }
    }

    public void renderPagination(Inventory inv, MenuHolder holder, int size, Player viewer, Team viewerTeam,
                                 ConfigurationSection menu) {
        // unchanged (your existing body)
        ConfigurationSection pag = menu.getConfigurationSection("pagination");
        if (pag == null) return;
        if (!plugin.menus().bool(pag, "enabled", false)) return;

        ConfigurationSection list = menu.getConfigurationSection("list");
        if (list == null) return;

        String type = plugin.menus().str(list, "type", "").toUpperCase(Locale.ROOT);
        List<Integer> slots = list.getIntegerList("slots");
        if (slots == null) slots = List.of();

        int perPage = plugin.menus().integer(pag, "per_page", Math.max(1, slots.size()));
        perPage = Math.max(1, perPage);

        int total = 0;

        if (type.equals("INVITES")) total = plugin.teams().getInvites(viewer.getUniqueId()).size();
        if (type.equals("TEAMS") && plugin.teams() instanceof SimpleTeamService sts) total = sts.allTeams().size();

        if (type.equals("TEAM_MEMBERS")) {
            Team targetTeam = viewerTeam;
            String ctxTeamId = holder.ctxGet("team_id");
            if (ctxTeamId != null && !ctxTeamId.isBlank()) {
                UUID tid = MenuIds.safeUuid(ctxTeamId);
                if (tid != null) targetTeam = plugin.teams().getTeamById(tid).orElse(null);
            }
            if (targetTeam != null) total = MenuTeams.uniqueTeamMembers(targetTeam).size();
        }

        if (type.equals("TEAM_HOMES") && viewerTeam != null) total = getTeamHomeNames(viewerTeam).size();

        int pages = Math.max(1, (total + perPage - 1) / perPage);

        boolean hideIfSingle = plugin.menus().bool(pag, "hide_if_single_page", true);
        if (hideIfSingle && pages <= 1) return;

        int page = holder.page();
        if (page >= pages) page = pages - 1;

        final int pageDisplay = page + 1;
        final int pagesFinal = pages;

        ConfigurationSection prev = pag.getConfigurationSection("prev");
        if (prev != null && page > 0) {
            int slot = Menus.clampSlot(prev.getInt("slot", 18), size);

            String prevName = plugin.menus().str(prev, "name", "&c← Prev")
                    .replace("{page}", String.valueOf(pageDisplay))
                    .replace("{pages}", String.valueOf(pagesFinal));

            List<String> prevLore = plugin.menus().strList(prev, "lore").stream()
                    .map(x -> Msg.color(x.replace("{page}", String.valueOf(pageDisplay))
                            .replace("{pages}", String.valueOf(pagesFinal))))
                    .toList();

            ItemStack it = MenuItems.item(
                    plugin.menus().material(prev, "material", Material.ARROW),
                    Msg.color(prevName),
                    prevLore
            );

            inv.setItem(slot, it);
            holder.bind(slot, "PAGE:PREV", false);
        }

        ConfigurationSection next = pag.getConfigurationSection("next");
        if (next != null && page < pages - 1) {
            int slot = Menus.clampSlot(next.getInt("slot", 26), size);

            String nextName = plugin.menus().str(next, "name", "&aNext →")
                    .replace("{page}", String.valueOf(pageDisplay))
                    .replace("{pages}", String.valueOf(pagesFinal));

            List<String> nextLore = plugin.menus().strList(next, "lore").stream()
                    .map(x -> Msg.color(x.replace("{page}", String.valueOf(pageDisplay))
                            .replace("{pages}", String.valueOf(pagesFinal))))
                    .toList();

            ItemStack it = MenuItems.item(
                    plugin.menus().material(next, "material", Material.ARROW),
                    Msg.color(nextName),
                    nextLore
            );

            inv.setItem(slot, it);
            holder.bind(slot, "PAGE:NEXT", false);
        }
    }

    // --------------------
    // Team Info: home bed rendering (unchanged)
    // --------------------
    public void renderTeamHomeBed(Inventory inv, MenuHolder holder, int size, Player viewer, Team team, ConfigurationSection items) {
        // your existing body
        if (inv == null || holder == null || viewer == null || team == null || items == null) return;

        ConfigurationSection sec = items.getConfigurationSection("team_home");
        if (sec == null) return;

        int slot = Menus.clampSlot(sec.getInt("slot", 14), size);

        boolean isOwner = team.getOwner() != null && team.getOwner().equals(viewer.getUniqueId());
        List<String> homes = getTeamHomeNames(team);

        int maxHomes = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));

        if (homes.isEmpty()) {
            ConfigurationSection whenNone = sec.getConfigurationSection("when_none");
            if (whenNone == null) return;

            Material mat = plugin.menus().material(whenNone, "material", Material.GRAY_BED);
            String name = text.apply(viewer, team, plugin.menus().str(whenNone, "name", "&7Team Home"));
            List<String> lore = text.applyList(viewer, team, plugin.menus().strList(whenNone, "lore"));

            inv.setItem(slot, MenuItems.item(mat, name, lore));

            String action = "NONE";
            if (isOwner) {
                if (maxHomes <= 1) action = "TEAMHOME:SET";
                else action = plugin.menus().str(whenNone, "action_owner", "TEAMHOME:SET");
            } else {
                action = plugin.menus().str(whenNone, "action_member", "NONE");
            }

            holder.bind(slot, action, plugin.menus().bool(whenNone, "close", false));
            return;
        }

        if (homes.size() == 1) {
            ConfigurationSection whenOne = sec.getConfigurationSection("when_one");
            if (whenOne == null) return;

            Material mat = plugin.menus().material(whenOne, "material", Material.RED_BED);
            String name = text.apply(viewer, team, plugin.menus().str(whenOne, "name", "&cTeam Home"));
            List<String> lore = text.applyList(viewer, team, plugin.menus().strList(whenOne, "lore"));

            inv.setItem(slot, MenuItems.item(mat, name, lore));

            String action;
            if (maxHomes <= 1) {
                action = plugin.menus().str(whenOne, "action", "TEAMHOME:TELEPORT_ONE");
            } else {
                action = "TEAMHOME:TELEPORT:" + homes.get(0);
            }

            boolean close = plugin.menus().bool(whenOne, "close", true);
            holder.bind(slot, action, close);
            return;
        }

        ConfigurationSection whenMany = sec.getConfigurationSection("when_many");
        if (whenMany == null) return;

        Material mat = plugin.menus().material(whenMany, "material", Material.RED_BED);
        String name = text.apply(viewer, team, plugin.menus().str(whenMany, "name", "&cTeam Homes"));
        List<String> lore = text.applyList(viewer, team, plugin.menus().strList(whenMany, "lore"));

        inv.setItem(slot, MenuItems.item(mat, name, lore));

        String action = plugin.menus().str(whenMany, "action", "OPEN:team_homes");
        boolean close = plugin.menus().bool(whenMany, "close", false);
        holder.bind(slot, action, close);
    }

    private List<String> getTeamHomeNames(Team team) {
        if (team == null) return List.of();
        if (plugin.teamHomes() == null) return List.of();

        List<TeamHome> homes = plugin.teamHomes().listHomes(team.getId());
        if (homes == null || homes.isEmpty()) return List.of();

        List<String> names = new ArrayList<>();
        for (TeamHome h : homes) {
            if (h == null) continue;
            if (h.getName() == null || h.getName().isBlank()) continue;
            names.add(h.getName());
        }
        return names;
    }

    private static String formatExpires(long ms) {
        try {
            return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(ms);
        }
    }

    private static <T> List<T> slice(List<T> list, int start, int count) {
        if (list == null || list.isEmpty()) return List.of();
        if (start < 0) start = 0;
        if (start >= list.size()) return List.of();
        int end = Math.min(list.size(), start + count);
        return new ArrayList<>(list.subList(start, end));
    }
}
