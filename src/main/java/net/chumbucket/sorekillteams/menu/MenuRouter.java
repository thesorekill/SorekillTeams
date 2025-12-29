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
import net.chumbucket.sorekillteams.model.TeamInvite;
import net.chumbucket.sorekillteams.util.Menus;
import net.chumbucket.sorekillteams.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MenuRouter {

    private final SorekillTeamsPlugin plugin;

    private final MenuText text;
    private final MenuSkullFactory skulls;
    private final MenuCycler cycler;
    private final MenuListRenderer lists;
    private final ConfirmMenuBuilder confirms;

    public MenuRouter(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;

        this.text = new MenuText(plugin);
        this.skulls = new MenuSkullFactory(plugin, text);
        this.cycler = new MenuCycler(plugin, skulls);
        this.lists = new MenuListRenderer(plugin, text, skulls, cycler);
        this.confirms = new ConfirmMenuBuilder(plugin, text, skulls);
    }

    public void open(Player p, String menuKey) {
        open(p, menuKey, 0, null);
    }

    public void stopCycling(UUID viewerId) {
        cycler.stopCycling(viewerId);
    }

    // =========================================================
    // ✅ Menu state helpers (refresh/close)
    // =========================================================

    /**
     * Re-open the menu the player currently has open (preserves page + ctx).
     */
    public void refreshOpenMenu(Player p) {
        if (p == null || !p.isOnline()) return;

        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return;

        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        String menuKey = holder.menuKey();
        int page = holder.page();

        Map<String, String> ctx = null;
        String teamId = holder.ctxGet("team_id");
        if (teamId != null && !teamId.isBlank()) {
            ctx = new HashMap<>();
            ctx.put("team_id", teamId);
        }

        open(p, menuKey, page, ctx);
    }

    public boolean isViewingMenu(Player p, String menuKey) {
        if (p == null || !p.isOnline() || menuKey == null) return false;
        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return false;
        if (!(top.getHolder() instanceof MenuHolder holder)) return false;
        return menuKey.equalsIgnoreCase(holder.menuKey());
    }

    public String openMenuKey(Player p) {
        if (p == null || !p.isOnline()) return null;
        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return null;
        if (!(top.getHolder() instanceof MenuHolder holder)) return null;
        return holder.menuKey();
    }

    public UUID openTeamId(Player p) {
        if (p == null || !p.isOnline()) return null;
        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return null;
        if (!(top.getHolder() instanceof MenuHolder holder)) return null;

        String id = holder.ctxGet("team_id");
        return MenuIds.safeUuid(id);
    }

    public void closeIfViewing(Player p, String... menuKeys) {
        if (p == null || !p.isOnline() || menuKeys == null || menuKeys.length == 0) return;

        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return;

        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        String openKey = holder.menuKey();
        if (openKey == null) return;

        for (String k : menuKeys) {
            if (k != null && !k.isBlank() && openKey.equalsIgnoreCase(k)) {
                p.closeInventory();
                return;
            }
        }
    }

    /**
     * Close team menus ONLY if they are displaying the given teamId.
     */
    public void closeIfViewingTeam(Player p, UUID teamId, String... menuKeys) {
        if (p == null || !p.isOnline() || teamId == null || menuKeys == null || menuKeys.length == 0) return;

        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return;

        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        String openKey = holder.menuKey();
        if (openKey == null) return;

        boolean matchesMenu = false;
        for (String k : menuKeys) {
            if (k != null && !k.isBlank() && openKey.equalsIgnoreCase(k)) {
                matchesMenu = true;
                break;
            }
        }
        if (!matchesMenu) return;

        UUID openTeam = MenuIds.safeUuid(holder.ctxGet("team_id"));
        if (openTeam != null && openTeam.equals(teamId)) {
            p.closeInventory();
        }
    }

    /**
     * Refresh team menus for local players who are currently viewing the affected team.
     */
    public void refreshIfViewingTeam(Player p, UUID teamId, String... menuKeys) {
        if (p == null || !p.isOnline() || teamId == null || menuKeys == null || menuKeys.length == 0) return;

        Inventory top = (p.getOpenInventory() != null) ? p.getOpenInventory().getTopInventory() : null;
        if (top == null) return;

        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        String openKey = holder.menuKey();
        if (openKey == null) return;

        boolean matchesMenu = false;
        for (String k : menuKeys) {
            if (k != null && !k.isBlank() && openKey.equalsIgnoreCase(k)) {
                matchesMenu = true;
                break;
            }
        }
        if (!matchesMenu) return;

        UUID openTeam = MenuIds.safeUuid(holder.ctxGet("team_id"));
        if (openTeam != null && openTeam.equals(teamId)) {
            refreshOpenMenu(p);
        }
    }

    /**
     * Convenience: refresh team_info + team_members for everyone viewing that team.
     */
    public void refreshTeamMenusForLocalViewers(UUID teamId) {
        if (teamId == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            refreshIfViewingTeam(p, teamId, "team_info", "team_members");
        }
    }

    /**
     * Convenience: close team_info + team_members for everyone viewing that team.
     */
    public void closeTeamMenusForLocalViewers(UUID teamId) {
        if (teamId == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            closeIfViewingTeam(p, teamId, "team_info", "team_members");
        }
    }

    // =========================================================

    private void open(Player p, String menuKey, int page, Map<String, String> ctx) {
        if (plugin == null || p == null) return;
        if (plugin.menus() == null) return;
        if (!plugin.menus().enabledInConfigYml()) return;

        cycler.stopCycling(p.getUniqueId());

        ConfigurationSection menu = plugin.menus().menu(menuKey);
        if (menu == null) return;

        plugin.ensureTeamsSnapshotFreshFromSql();
        plugin.ensureTeamFreshFromSql(p);

        Team viewerTeam = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);

        // ✅ Make sure ctx carries team_id for team menus even when opened without payload
        Map<String, String> ctxFinal = ctx;
        if (ctxFinal == null) ctxFinal = new HashMap<>();

        boolean isTeamMenu = menuKey != null && (
                "team_info".equalsIgnoreCase(menuKey) ||
                        "team_members".equalsIgnoreCase(menuKey)
        );

        if (isTeamMenu && viewerTeam != null && viewerTeam.getId() != null) {
            ctxFinal.putIfAbsent("team_id", viewerTeam.getId().toString());
        }

        Team placeholderTeamForTitle = resolvePlaceholderTeamForMenu(menuKey, viewerTeam, ctxFinal);

        String rawTitle = plugin.menus().str(menu, "title", "&8ᴛᴇᴀᴍꜱ");
        String title = text.apply(p, placeholderTeamForTitle, rawTitle);

        int rows = Menus.clampRows(plugin.menus().integer(menu, "rows", 3));
        int size = rows * 9;

        MenuHolder holder = new MenuHolder(menuKey, p);
        holder.setPage(Math.max(0, page));

        if (ctxFinal != null) {
            for (Map.Entry<String, String> e : ctxFinal.entrySet()) holder.ctxPut(e.getKey(), e.getValue());
        }

        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        fillBackground(inv, size, menu);

        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items != null) {

            renderTeamOrCreate(inv, holder, size, p, viewerTeam, items);

            if ("team_info".equalsIgnoreCase(menuKey) && viewerTeam != null) {

                bindStatic(inv, holder, size, p, viewerTeam, items, "name_tag", actionForNameTag(p, viewerTeam));
                bindStatic(inv, holder, size, p, viewerTeam, items, "team_chat", "TEAMCHAT:TOGGLE");
                bindStatic(inv, holder, size, p, viewerTeam, items, "friendly_fire", "FF:TOGGLE");

                ConfigurationSection mh = items.getConfigurationSection("members_head");
                if (mh != null) {
                    int slot = Menus.clampSlot(mh.getInt("slot", 13), size);

                    ItemStack init = skulls.buildMemberCycleHead(p, viewerTeam, mh, 0);
                    inv.setItem(slot, init);

                    String action = plugin.menus().str(mh, "action", "OPEN:team_members");
                    boolean close = plugin.menus().bool(mh, "close", false);
                    holder.bind(slot, action, close);

                    cycler.startMemberHeadCycling(p, holder, slot, mh);
                }

                // ✅ FIX: homes can be set on other backends; refresh homes snapshot before rendering bed.
                // This ensures the menu always shows the current SQL state (no stale cache).
                try {
                    if (plugin.teamHomes() != null) {
                        // This method exists in your plugin already (used at startup).
                        // It should load from the active home storage (SQL/YAML) safely.
                        plugin.loadHomesBestEffort("menu_open");
                    }
                } catch (Throwable ignored) {}

                lists.renderTeamHomeBed(inv, holder, size, p, viewerTeam, items);

            } else {
                bindStatic(inv, holder, size, p, viewerTeam, items, "invites", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "browse_teams", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "close", null);
                bindStatic(inv, holder, size, p, viewerTeam, items, "header", null);
            }
        }

        ConfigurationSection list = menu.getConfigurationSection("list");
        if (list != null) {
            lists.renderDynamicList(menuKey, inv, holder, size, p, viewerTeam, list);
            lists.renderPagination(inv, holder, size, p, viewerTeam, menu);
        }

        p.openInventory(inv);
    }

    private void fillBackground(Inventory inv, int size, ConfigurationSection menu) {
        ConfigurationSection filler = menu.getConfigurationSection("filler");
        boolean fillerEnabled = plugin.menus().bool(filler, "enabled", true);
        if (!fillerEnabled) return;

        Material fillMat = plugin.menus().material(filler, "material", Material.GRAY_STAINED_GLASS_PANE);
        String fillName = Msg.color(plugin.menus().str(filler, "name", " "));
        ItemStack fill = MenuItems.item(fillMat, fillName, java.util.List.of());

        for (int i = 0; i < size; i++) inv.setItem(i, fill);
    }

    private void renderTeamOrCreate(Inventory inv, MenuHolder holder, int size, Player p, Team viewerTeam, ConfigurationSection items) {
        ConfigurationSection toc = items.getConfigurationSection("team_or_create");
        if (toc == null) return;

        int slot = Menus.clampSlot(toc.getInt("slot", 11), size);

        ConfigurationSection chosen = (viewerTeam == null)
                ? toc.getConfigurationSection("when_not_in_team")
                : toc.getConfigurationSection("when_in_team");

        if (chosen == null) return;

        ItemStack it = MenuItems.item(
                plugin.menus().material(chosen, "material", Material.BOOK),
                text.apply(p, viewerTeam, plugin.menus().str(chosen, "name", "&bTeam")),
                text.applyList(p, viewerTeam, plugin.menus().strList(chosen, "lore"))
        );

        inv.setItem(slot, it);

        String action = plugin.menus().str(chosen, "action", "NONE");
        boolean close = plugin.menus().bool(chosen, "close", false);
        holder.bind(slot, action, close);
    }

    public void runAction(Player p, String action) {
        if (p == null || action == null) return;
        String a = action.trim();
        if (a.isEmpty() || a.equalsIgnoreCase("NONE")) return;

        if (a.regionMatches(true, 0, "OPEN:", 0, "OPEN:".length())) {
            String rest = a.substring("OPEN:".length()).trim();
            if (rest.isEmpty()) return;

            String[] parts = rest.split(":", 2);
            String key = parts[0].trim();
            String payload = (parts.length > 1 ? parts[1].trim() : "");

            if (key.isEmpty()) return;

            if (!payload.isEmpty()) {
                Map<String, String> ctx = new HashMap<>();
                ctx.put("team_id", payload);
                open(p, key, 0, ctx);
            } else {
                open(p, key, 0, null);
            }
            return;
        }

        if (a.regionMatches(true, 0, "PAGE:", 0, "PAGE:".length())) {
            String dir = a.substring("PAGE:".length()).trim().toUpperCase(Locale.ROOT);

            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)) return;

            int newPage = holder.page();
            if (dir.equals("NEXT")) newPage++;
            if (dir.equals("PREV")) newPage = Math.max(0, newPage - 1);

            Map<String, String> ctx = new HashMap<>();
            String teamId = holder.ctxGet("team_id");
            if (teamId != null) ctx.put("team_id", teamId);

            open(p, holder.menuKey(), newPage, ctx.isEmpty() ? null : ctx);
            return;
        }

        if (a.regionMatches(true, 0, "FLOW:", 0, "FLOW:".length())) {
            String flow = a.substring("FLOW:".length()).trim().toUpperCase(Locale.ROOT);
            if (flow.equals("CREATE_TEAM")) {
                CreateTeamFlow.begin(plugin, p);
            }
            return;
        }

        if (a.regionMatches(true, 0, "COMMAND:", 0, "COMMAND:".length())) {
            String cmd = a.substring("COMMAND:".length()).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isBlank()) p.performCommand(cmd);
            return;
        }

        if (a.regionMatches(true, 0, "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:", 0,
                "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:".length())) {
            String id = a.substring("CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:".length()).trim();
            UUID teamId = MenuIds.safeUuid(id);
            deniesInviteAndReturn(p, teamId);
            return;
        }

        if (a.regionMatches(true, 0, "CONFIRM:", 0, "CONFIRM:".length())) {
            String rest = a.substring("CONFIRM:".length()).trim();
            openConfirmFromAction(p, rest);
            return;
        }

        if (a.equalsIgnoreCase("TEAMCHAT:TOGGLE")) {
            if (!plugin.isTeamChatToggleEnabled()) {
                plugin.msg().send(p, "teamchat_toggle_disabled");
                return;
            }

            plugin.teams().toggleTeamChat(p.getUniqueId());

            boolean enabled = plugin.teams().isTeamChatEnabled(p.getUniqueId());
            plugin.msg().send(p, enabled ? "teamchat_on" : "teamchat_off");

            open(p, "team_info", 0, null);
            return;
        }

        if (a.equalsIgnoreCase("FF:TOGGLE")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t == null) return;
            if (!p.getUniqueId().equals(t.getOwner())) return;

            p.performCommand("team ff toggle");
            plugin.getServer().getScheduler().runTask(plugin, () -> open(p, "team_info", 0, null));
            return;
        }

        if (a.equalsIgnoreCase("TEAMHOME:SET")) {
            int max = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));
            if (max <= 1) {
                p.performCommand("team sethome team");
                plugin.getServer().getScheduler().runTask(plugin, () -> refreshOpenMenu(p));
            } else {
                p.performCommand("team sethome");
            }
            return;
        }

        if (a.equalsIgnoreCase("TEAMHOME:TELEPORT_ONE")) {
            int max = Math.max(1, plugin.getConfig().getInt("homes.max_homes", 1));
            if (max <= 1) p.performCommand("team home team");
            else p.performCommand("team home");
            return;
        }

        if (a.regionMatches(true, 0, "TEAMHOME:TELEPORT:", 0, "TEAMHOME:TELEPORT:".length())) {
            String homeName = a.substring("TEAMHOME:TELEPORT:".length()).trim();
            if (!homeName.isBlank()) p.performCommand("team home " + homeName);
            return;
        }

        if (a.equalsIgnoreCase("CLOSE")) {
            p.closeInventory();
        }
    }

    private void openConfirmFromAction(Player p, String rest) {
        if (p == null || rest == null) return;

        String[] parts = rest.split(":", 2);
        String variant = parts[0].trim().toLowerCase(Locale.ROOT);
        String payload = (parts.length > 1 ? parts[1].trim() : "");

        confirms.openConfirmMenu(p, variant, payload);
    }

    private void deniesInviteAndReturn(Player p, UUID teamId) {
        if (p == null || teamId == null) return;
        p.performCommand("team deny " + teamId);
        plugin.getServer().getScheduler().runTask(plugin, () -> open(p, "invites", 0, null));
    }

    private String actionForNameTag(Player viewer, Team team) {
        if (viewer == null || team == null) return "NONE";
        boolean isOwner = team.getOwner() != null && team.getOwner().equals(viewer.getUniqueId());
        return isOwner ? "CONFIRM:disband" : "CONFIRM:leave";
    }

    private void bindStatic(Inventory inv, MenuHolder holder, int size, Player viewer, Team team,
                            ConfigurationSection items, String key, String overrideAction) {

        ConfigurationSection s = items.getConfigurationSection(key);
        if (s == null) return;

        final int slot = Menus.clampSlot(s.getInt("slot", 0), size);

        Material mat = plugin.menus().material(s, "material", Material.STONE);
        final String name = text.apply(viewer, team, plugin.menus().str(s, "name", "&fItem"));
        final java.util.List<String> lore = text.applyList(viewer, team, plugin.menus().strList(s, "lore"));

        ItemStack it;
        if (mat == Material.PLAYER_HEAD) {
            final UUID headUuid = (team == null ? null : team.getOwner());

            it = skulls.buildPlayerHead(headUuid, name, lore, () -> {
                if (!viewer.isOnline()) return;
                if (viewer.getOpenInventory() == null) return;
                Inventory top = viewer.getOpenInventory().getTopInventory();
                if (top == null) return;
                if (top.getHolder() != holder) return;
                top.setItem(slot, skulls.buildPlayerHead(headUuid, name, lore));
            });
        } else {
            it = MenuItems.item(mat, name, lore);
        }

        inv.setItem(slot, it);

        String action = (overrideAction != null ? overrideAction : plugin.menus().str(s, "action", "NONE"));
        boolean close = plugin.menus().bool(s, "close", false);
        holder.bind(slot, action, close);
    }

    private Team resolvePlaceholderTeamForMenu(String menuKey, Team viewerTeam, Map<String, String> ctx) {
        if (menuKey == null) return viewerTeam;

        if ("team_members".equalsIgnoreCase(menuKey)) {
            String id = (ctx == null ? null : ctx.get("team_id"));
            if (id != null && !id.isBlank()) {
                UUID tid = MenuIds.safeUuid(id);
                if (tid != null) {
                    return plugin.teams().getTeamById(tid).orElse(null);
                }
            }
        }

        return viewerTeam;
    }

    TeamInvite findInvite(UUID invitee, UUID teamId) {
        if (invitee == null || teamId == null) return null;
        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            if (inv == null) continue;
            if (teamId.equals(inv.getTeamId())) return inv;
        }
        return null;
    }
}
