/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.listener;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import net.chumbucket.sorekillteams.model.Team;
import net.chumbucket.sorekillteams.service.TeamService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FriendlyFireListener implements Listener {

    private static final String MSG_KEY_BLOCKED = "friendly_fire_blocked";

    private final SorekillTeamsPlugin plugin;

    // attacker -> last message timestamp (ms)
    private final Map<UUID, Long> messageCooldown = new ConcurrentHashMap<>();

    public FriendlyFireListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {

        // ✅ Semantics:
        // enabled: true  => friendly fire ALLOWED globally => do nothing
        // enabled: false => friendly fire BLOCKED globally => apply team rules
        if (plugin.getConfig().getBoolean("friendly_fire.enabled", true)) return;

        if (!(e.getEntity() instanceof Player victim)) return;

        boolean includeProjectiles = plugin.getConfig().getBoolean("friendly_fire.include_projectiles", true);

        Player attacker = resolveAttacker(e.getDamager(), includeProjectiles);
        if (attacker == null) return;

        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;

        TeamService teams = plugin.teams();
        if (teams == null) return;

        // Not teammates? allow
        if (!teams.areTeammates(attacker.getUniqueId(), victim.getUniqueId())) return;

        // ✅ Team-level override:
        // If the team's friendly fire is enabled, allow damage even though global is blocked.
        Optional<Team> teamOpt = teams.getTeamByPlayer(attacker.getUniqueId());
        if (teamOpt.isPresent() && teamOpt.get().isFriendlyFireEnabled()) {
            return; // allow teammate damage
        }

        // Otherwise block it
        e.setCancelled(true);

        // Optional message (from messages.yml)
        maybeMessage(attacker);
    }

    private Player resolveAttacker(Entity damager, boolean includeProjectiles) {
        if (damager instanceof Player p) {
            return p;
        }

        if (!includeProjectiles) {
            return null;
        }

        if (damager instanceof Projectile proj) {
            Object shooter = proj.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }

        return null;
    }

    private void maybeMessage(Player attacker) {
        boolean msgEnabled = plugin.getConfig().getBoolean("friendly_fire.message.enabled", true);
        if (!msgEnabled) return;

        long cooldownMs = plugin.getConfig().getLong("friendly_fire.message.cooldown_ms", 1000L);
        if (cooldownMs > 0) {
            long now = System.currentTimeMillis();
            long last = messageCooldown.getOrDefault(attacker.getUniqueId(), 0L);
            if (now - last < cooldownMs) return;
            messageCooldown.put(attacker.getUniqueId(), now);
        }

        if (plugin.msg() != null) {
            plugin.msg().send(attacker, MSG_KEY_BLOCKED);
        }
    }
}