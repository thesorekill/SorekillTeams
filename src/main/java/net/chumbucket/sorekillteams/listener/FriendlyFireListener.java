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
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public final class FriendlyFireListener implements Listener {

    private final SorekillTeamsPlugin plugin;

    public FriendlyFireListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("friendly_fire.enabled", true)) return;
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;

        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (plugin.getConfig().getBoolean("friendly_fire.include_projectiles", true)
                && e.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null) return;
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;

        if (plugin.teams().areTeammates(attacker.getUniqueId(), victim.getUniqueId())) {
            e.setCancelled(true);
        }
    }
}
