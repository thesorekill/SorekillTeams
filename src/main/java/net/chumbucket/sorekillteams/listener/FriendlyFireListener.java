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
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

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

        // ✅ Option A semantics (matches your config.yml comments):
        // enabled: true  => friendly fire ALLOWED globally => do nothing
        // enabled: false => friendly fire BLOCKED globally => apply team rules
        if (plugin.getConfig().getBoolean("friendly_fire.enabled", false)) return;

        if (!(e.getEntity() instanceof Player victim)) return;

        boolean includeProjectiles = plugin.getConfig().getBoolean("friendly_fire.include_projectiles", true);
        boolean includeClouds = plugin.getConfig().getBoolean("friendly_fire.include_area_effect_clouds", true);
        boolean includeExplosives = plugin.getConfig().getBoolean("friendly_fire.include_explosives", true);

        Player attacker = resolveAttacker(e.getDamager(), includeProjectiles, includeClouds, includeExplosives);
        if (attacker == null) return;

        // allow self-damage
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;

        TeamService teams = plugin.teams();
        if (teams == null) return;

        // Not teammates? allow
        if (!teams.areTeammates(attacker.getUniqueId(), victim.getUniqueId())) return;

        // ✅ Team-level override: allow teammate damage if team FF is enabled
        Optional<Team> teamOpt = teams.getTeamByPlayer(attacker.getUniqueId());
        if (teamOpt.isPresent() && teamOpt.get().isFriendlyFireEnabled()) return;

        // Otherwise block it
        e.setCancelled(true);

        // Optional message (from messages.yml)
        maybeMessage(attacker);
    }

    private Player resolveAttacker(Entity damager,
                                  boolean includeProjectiles,
                                  boolean includeClouds,
                                  boolean includeExplosives) {

        // direct melee
        if (damager instanceof Player p) return p;

        // projectiles (arrows, tridents, snowballs, splash potions, etc.)
        if (includeProjectiles && damager instanceof Projectile proj) {
            Object shooter = proj.getShooter();
            if (shooter instanceof Player p) return p;
        }

        // lingering potion / AoE cloud attribution
        if (includeClouds && damager instanceof AreaEffectCloud cloud) {
            ProjectileSource src = cloud.getSource();
            if (src instanceof Player p) return p;
        }

        // TNTPrimed source attribution
        if (includeExplosives && damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player p) return p;
        }

        return null;
    }

    private void maybeMessage(Player attacker) {
        if (!plugin.getConfig().getBoolean("friendly_fire.message.enabled", true)) return;

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