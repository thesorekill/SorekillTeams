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
import net.chumbucket.sorekillteams.util.Menus;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles:
 * - team_info member head cycle (single slot)
 * - browse_teams head cycle (many slots)
 *
 * Logic matches your original (same stop conditions, same intervals).
 */
public final class MenuCycler {

    private static final long MEMBER_HEAD_CYCLE_TICKS = 40L; // 2s
    private static final long BROWSE_HEAD_CYCLE_TICKS = 40L; // 2s

    private final SorekillTeamsPlugin plugin;
    private final MenuSkullFactory skulls;

    // team_info member head cycle (single slot)
    private final Map<UUID, Integer> memberCycleTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> memberCycleIndex = new ConcurrentHashMap<>();

    // browse_teams cycle (many slots)
    private final Map<UUID, Integer> browseCycleTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Integer>> browseCycleIndexByTeam = new ConcurrentHashMap<>();

    public MenuCycler(SorekillTeamsPlugin plugin, MenuSkullFactory skulls) {
        this.plugin = plugin;
        this.skulls = skulls;
    }

    public void startMemberHeadCycling(Player viewer, MenuHolder holder, int slot, ConfigurationSection membersHeadSec) {
        UUID viewerId = viewer.getUniqueId();

        stopCycling(viewerId);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline()) {
                stopCycling(viewerId);
                return;
            }

            if (viewer.getOpenInventory() == null
                    || viewer.getOpenInventory().getTopInventory() == null
                    || viewer.getOpenInventory().getTopInventory().getHolder() != holder) {
                stopCycling(viewerId);
                return;
            }

            if (!"team_info".equalsIgnoreCase(holder.menuKey())) {
                stopCycling(viewerId);
                return;
            }

            Team team = plugin.teams().getTeamByPlayer(viewerId).orElse(null);
            if (team == null) {
                stopCycling(viewerId);
                return;
            }

            List<UUID> members = MenuTeams.uniqueTeamMembers(team);
            if (members.isEmpty()) return;

            int idx = memberCycleIndex.getOrDefault(viewerId, 0);
            int nextIdx = (idx + 1) % members.size();
            memberCycleIndex.put(viewerId, nextIdx);

            Inventory top = viewer.getOpenInventory().getTopInventory();
            ItemStack head = skulls.buildMemberCycleHead(viewer, team, membersHeadSec, nextIdx);
            top.setItem(slot, head);

        }, 1L, MEMBER_HEAD_CYCLE_TICKS).getTaskId();

        memberCycleTasks.put(viewerId, taskId);
        memberCycleIndex.putIfAbsent(viewerId, 0);
    }

    public void startBrowseTeamsCycling(Player viewer,
                                        MenuHolder holder,
                                        List<Integer> slots,
                                        List<Team> pageTeams,
                                        int invSize,
                                        String nameTemplate,
                                        List<String> loreTemplate,
                                        java.util.function.Function<UUID, String> nameOf,
                                        java.util.function.ToIntFunction<Team> memberCount,
                                        java.util.function.Function<Team, List<UUID>> memberList) {

        UUID viewerId = viewer.getUniqueId();

        Integer old = browseCycleTasks.remove(viewerId);
        if (old != null) {
            try { Bukkit.getScheduler().cancelTask(old); } catch (Exception ignored) {}
        }

        browseCycleIndexByTeam.putIfAbsent(viewerId, new ConcurrentHashMap<>());

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline()) {
                stopCycling(viewerId);
                return;
            }

            if (viewer.getOpenInventory() == null
                    || viewer.getOpenInventory().getTopInventory() == null
                    || viewer.getOpenInventory().getTopInventory().getHolder() != holder) {
                stopCycling(viewerId);
                return;
            }

            if (!"browse_teams".equalsIgnoreCase(holder.menuKey())) {
                Integer tid = browseCycleTasks.remove(viewerId);
                if (tid != null) {
                    try { Bukkit.getScheduler().cancelTask(tid); } catch (Exception ignored) {}
                }
                return;
            }

            Inventory top = viewer.getOpenInventory().getTopInventory();
            Map<UUID, Integer> idxMap = browseCycleIndexByTeam.get(viewerId);
            if (top == null || idxMap == null) return;

            for (int i = 0; i < Math.min(slots.size(), pageTeams.size()); i++) {
                int slot = Menus.clampSlot(slots.get(i), invSize);
                Team team = pageTeams.get(i);
                if (team == null || team.getId() == null) continue;

                List<UUID> members = memberList.apply(team);
                if (members.isEmpty()) continue;

                int idx = idxMap.getOrDefault(team.getId(), 0);
                int next = (idx + 1) % members.size();
                idxMap.put(team.getId(), next);

                String owner = nameOf.apply(team.getOwner());
                String membersCountStr = String.valueOf(memberCount.applyAsInt(team));

                ItemStack head = skulls.buildBrowseTeamCycleHead(viewer, team, next, nameTemplate, loreTemplate, owner, membersCountStr);
                top.setItem(slot, head);
            }

        }, 1L, BROWSE_HEAD_CYCLE_TICKS).getTaskId();

        browseCycleTasks.put(viewerId, taskId);
    }

    public void stopCycling(UUID viewerId) {
        if (viewerId == null) return;

        Integer t1 = memberCycleTasks.remove(viewerId);
        if (t1 != null) {
            try { Bukkit.getScheduler().cancelTask(t1); } catch (Exception ignored) {}
        }
        memberCycleIndex.remove(viewerId);

        Integer t2 = browseCycleTasks.remove(viewerId);
        if (t2 != null) {
            try { Bukkit.getScheduler().cancelTask(t2); } catch (Exception ignored) {}
        }
        browseCycleIndexByTeam.remove(viewerId);
    }
}
