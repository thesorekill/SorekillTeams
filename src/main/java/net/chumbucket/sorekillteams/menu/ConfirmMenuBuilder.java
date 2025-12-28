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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Confirm menu (variants) extracted from MenuRouter.
 * Same behavior, just isolated.
 *
 * ✅ Fix: subject skull now re-renders after async Paper profile fetch completes,
 * so offline skins show up without requiring cycling or reopening.
 */
public final class ConfirmMenuBuilder {

    private final SorekillTeamsPlugin plugin;
    private final MenuText text;
    private final MenuSkullFactory skulls;

    public ConfirmMenuBuilder(SorekillTeamsPlugin plugin, MenuText text, MenuSkullFactory skulls) {
        this.plugin = plugin;
        this.text = text;
        this.skulls = skulls;
    }

    public void openConfirmMenu(Player p, String variant, String payload) {
        if (plugin.menus() == null) return;

        ConfigurationSection root = plugin.menus().menu("confirm");
        if (root == null) return;

        ConfigurationSection variants = root.getConfigurationSection("variants");
        ConfigurationSection v = (variants == null ? null : variants.getConfigurationSection(variant));
        if (v == null) return;

        int rows = Menus.clampRows(plugin.menus().integer(root, "rows", 3));
        int size = rows * 9;

        String title = Msg.color(plugin.menus().str(v, "title", "&8ᴄᴏɴꜰɪʀᴍ"));

        MenuHolder holder = new MenuHolder("confirm:" + variant, p);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        // filler
        ConfigurationSection filler = root.getConfigurationSection("filler");
        boolean fillerEnabled = plugin.menus().bool(filler, "enabled", true);
        if (fillerEnabled) {
            Material fillMat = plugin.menus().material(filler, "material", Material.GRAY_STAINED_GLASS_PANE);
            String fillName = Msg.color(plugin.menus().str(filler, "name", " "));
            ItemStack fill = MenuItems.item(fillMat, fillName, List.of());
            for (int i = 0; i < size; i++) inv.setItem(i, fill);
        }

        ConfigurationSection layout = root.getConfigurationSection("layout");
        int denySlot = Menus.clampSlot(layout == null ? 10 : layout.getInt("deny_slot", 10), size);
        int subjSlot = Menus.clampSlot(layout == null ? 13 : layout.getInt("subject_slot", 13), size);
        int accSlot = Menus.clampSlot(layout == null ? 16 : layout.getInt("accept_slot", 16), size);

        ConfigurationSection buttons = root.getConfigurationSection("buttons");
        ConfigurationSection denyB = buttons == null ? null : buttons.getConfigurationSection("deny");
        ConfigurationSection accB = buttons == null ? null : buttons.getConfigurationSection("accept");
        ConfigurationSection subB = buttons == null ? null : buttons.getConfigurationSection("subject");

        String subjectName = "Confirm";
        String subtitle = plugin.menus().str(v, "subtitle", "");
        String acceptAction = plugin.menus().str(v, "default_accept_action", "NONE");
        String denyAction = plugin.menus().str(v, "default_deny_action", "NONE");
        UUID subjectHeadUuid = null;

        String variantKey = variant == null ? "" : variant.trim().toLowerCase(Locale.ROOT);

        if (variantKey.equals("invite")) {
            UUID teamId = MenuIds.safeUuid(payload);
            if (teamId != null) {
                Team t = plugin.teams().getTeamById(teamId).orElse(null);
                if (t != null) {
                    subjectName = (t.getName() == null ? "Team" : t.getName());
                    subjectHeadUuid = t.getOwner();

                    TeamInvite invt = findInvite(p.getUniqueId(), teamId);
                    String inviter = invt == null ? "unknown" : text.nameOf(invt.getInviter());

                    subtitle = subtitle
                            .replace("{inviter}", inviter)
                            .replace("{team}", Msg.color(subjectName));

                    acceptAction = "COMMAND:team accept " + teamId;
                    denyAction = "CONFIRM_INTERNAL:DENY_INVITE_AND_RETURN:" + teamId;
                }
            }
        }

        if (variantKey.equals("disband")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t != null) {
                subjectName = (t.getName() == null ? "Team" : t.getName());
                subjectHeadUuid = t.getOwner();
                subtitle = subtitle.replace("{team}", Msg.color(subjectName));
                acceptAction = "COMMAND:team disband";
                denyAction = "OPEN:team_info";
            }
        }

        if (variantKey.equals("leave")) {
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            if (t != null) {
                subjectName = (t.getName() == null ? "Team" : t.getName());
                subjectHeadUuid = t.getOwner();
                subtitle = subtitle.replace("{team}", Msg.color(subjectName));
                acceptAction = "COMMAND:team leave";
                denyAction = "OPEN:team_info";
            }
        }

        if (variantKey.equals("kick_member")) {
            UUID member = MenuIds.safeUuid(payload);
            Team t = plugin.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);

            if (t != null && member != null) {
                subjectName = text.nameOf(member);
                subjectHeadUuid = member;

                subtitle = subtitle.replace("{member}", subjectName);
                acceptAction = "COMMAND:team kick " + subjectName;
                denyAction = "OPEN:team_members";
            }
        }

        if (denyB != null) {
            ItemStack it = MenuItems.item(
                    plugin.menus().material(denyB, "material", Material.RED_STAINED_GLASS_PANE),
                    Msg.color(plugin.menus().str(denyB, "name", "&cCANCEL")),
                    plugin.menus().strList(denyB, "lore")
            );
            inv.setItem(denySlot, it);
            boolean close = plugin.menus().bool(denyB, "close", true);
            holder.bind(denySlot, denyAction, close);
        }

        if (accB != null) {
            ItemStack it = MenuItems.item(
                    plugin.menus().material(accB, "material", Material.LIME_STAINED_GLASS_PANE),
                    Msg.color(plugin.menus().str(accB, "name", "&aCONFIRM")),
                    plugin.menus().strList(accB, "lore")
            );
            inv.setItem(accSlot, it);
            boolean close = plugin.menus().bool(accB, "close", true);
            holder.bind(accSlot, acceptAction, close);
        }

        String subjectLine = (subB == null)
                ? "&b{subject}"
                : plugin.menus().str(subB, "name", "&b{subject}");

        subjectLine = subjectLine.replace("{subject}", Msg.color(subjectName));

        final UUID headUuidFinal = subjectHeadUuid;
        final String subjectLineFinal = Msg.color(subjectLine);
        final String subtitleFinal = Msg.color(subtitle == null ? "" : subtitle);
        final int subjSlotFinal = subjSlot;

        // ✅ Refresh callback: re-render subject slot when profile finishes fetching
        Runnable refresh = () -> {
            if (!p.isOnline()) return;

            if (p.getOpenInventory() == null) return;
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top == null) return;

            if (!(top.getHolder() instanceof MenuHolder h)) return;
            if (h != holder) return; // must be same menu instance
            if (h.menuKey() == null || !h.menuKey().equalsIgnoreCase("confirm:" + variant)) return;

            ItemStack rebuilt = skulls.buildPlayerHead(
                    headUuidFinal,
                    subjectLineFinal,
                    List.of(subtitleFinal),
                    null
            );
            top.setItem(subjSlotFinal, rebuilt);
        };

        ItemStack subj = skulls.buildPlayerHead(
                subjectHeadUuid,
                subjectLineFinal,
                List.of(subtitleFinal),
                refresh
        );

        inv.setItem(subjSlot, subj);
        holder.bind(subjSlot, "NONE", false);

        p.openInventory(inv);
    }

    private TeamInvite findInvite(UUID invitee, UUID teamId) {
        if (invitee == null || teamId == null) return null;
        for (TeamInvite inv : plugin.teams().getInvites(invitee)) {
            if (inv == null) continue;
            if (teamId.equals(inv.getTeamId())) return inv;
        }
        return null;
    }
}
