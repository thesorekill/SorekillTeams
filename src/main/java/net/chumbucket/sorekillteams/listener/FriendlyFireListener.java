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
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FriendlyFireListener implements Listener {

    private static final String MSG_KEY_BLOCKED = "friendly_fire_blocked";
    private static final String BYPASS_PERMISSION = "sorekillteams.friendlyfire.bypass";

    private final SorekillTeamsPlugin plugin;

    // attacker -> last message timestamp (ms)
    private final Map<UUID, Long> messageCooldown = new ConcurrentHashMap<>();

    // Cached reflection for PotionEffectType#isBeneficial (exists on modern versions; keep safe)
    private static final Method IS_BENEFICIAL_METHOD = resolveIsBeneficialMethod();

    // Cached reflection for PotionEffectType#getKey (deprecated in some versions; we avoid compile-time call)
    private static final Method EFFECTTYPE_GETKEY_METHOD = resolveEffectTypeGetKeyMethod();

    public FriendlyFireListener(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {

        // enabled: true  => friendly fire ALLOWED globally => do nothing
        // enabled: false => friendly fire BLOCKED globally => apply team rules
        if (plugin.getConfig().getBoolean("friendly_fire.enabled", false)) return;

        if (!(e.getEntity() instanceof Player victim)) return;

        boolean includeProjectiles = plugin.getConfig().getBoolean("friendly_fire.include_projectiles", true);
        boolean includeClouds = plugin.getConfig().getBoolean("friendly_fire.include_area_effect_clouds", true);
        boolean includeExplosives = plugin.getConfig().getBoolean("friendly_fire.include_explosives", true);

        boolean includePotions = plugin.getConfig().getBoolean("friendly_fire.include_potions", true);
        boolean includeTridents = plugin.getConfig().getBoolean("friendly_fire.include_tridents", true);

        Player attacker = resolveAttacker(
                e.getDamager(),
                includeProjectiles,
                includeClouds,
                includeExplosives,
                includePotions,
                includeTridents
        );
        if (attacker == null) return;

        // bypass
        if (attacker.hasPermission(BYPASS_PERMISSION)) return;

        // allow self-damage
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;

        TeamService teams = plugin.teams();
        if (teams == null) return;

        // Not teammates? allow
        if (!teams.areTeammates(attacker.getUniqueId(), victim.getUniqueId())) return;

        // Team-level override: allow teammate damage if team FF is enabled
        Optional<Team> teamOpt = teams.getTeamByPlayer(attacker.getUniqueId());
        if (teamOpt.isPresent() && teamOpt.get().isFriendlyFireEnabled()) return;

        // Otherwise block it
        e.setCancelled(true);
        maybeMessage(attacker);
    }

    private Player resolveAttacker(Entity damager,
                                  boolean includeProjectiles,
                                  boolean includeClouds,
                                  boolean includeExplosives,
                                  boolean includePotions,
                                  boolean includeTridents) {

        // direct melee
        if (damager instanceof Player p) return p;

        // lingering potion / AoE cloud attribution
        if (includeClouds && damager instanceof AreaEffectCloud cloud) {
            if (includePotions) {
                // Allow purely beneficial clouds (healing/speed/etc.)
                if (isCloudNonAggressive(cloud)) {
                    return null;
                }
            }
            ProjectileSource src = cloud.getSource();
            if (src instanceof Player p) return p;
        }

        // TNTPrimed source attribution
        if (includeExplosives && damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player p) return p;
        }

        // projectiles (arrows, tridents, snowballs, potions, etc.)
        if (includeProjectiles && damager instanceof Projectile proj) {

            // Potion projectile: only treat as attack if it has ANY harmful effects
            if (proj instanceof ThrownPotion tp) {
                if (!includePotions) return null;

                // If potion is purely beneficial, do NOT route into FF logic
                if (isAllEffectsBeneficial(tp.getEffects())) {
                    return null;
                }
            }

            // Optionally exclude tridents from projectile attribution
            if (!includeTridents && proj.getClass().getSimpleName().equalsIgnoreCase("Trident")) {
                return null;
            }

            Object shooter = proj.getShooter();
            if (shooter instanceof Player p) return p;
        }

        return null;
    }

    /**
     * Cloud is non-aggressive if:
     * - its custom effects are all beneficial AND
     * - its base potion type (if we can read it) is not a known harmful type
     */
    private boolean isCloudNonAggressive(AreaEffectCloud cloud) {
        if (cloud == null) return true;

        // Correct API: custom effects
        if (!isAllEffectsBeneficial(cloud.getCustomEffects())) {
            return false;
        }

        // Base potion type/data: reflection for API variants
        String base = getCloudBasePotionName(cloud);
        if (base == null || base.isBlank()) return true;

        String n = base.trim().toLowerCase(Locale.ROOT);

        // treat these base potion types as harmful/aggressive
        return !(n.contains("instant_damage")
                || n.contains("harming")
                || n.contains("poison")
                || n.contains("weakness")
                || n.contains("slowness")
                || n.contains("wither")
                || n.contains("turtle_master"));
    }

    /**
     * Returns true if every effect in the collection is beneficial.
     * Uses PotionEffectType#isBeneficial when present; otherwise falls back to key matching.
     */
    private boolean isAllEffectsBeneficial(Collection<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            // No effects: treat as non-aggressive
            return true;
        }

        for (PotionEffect pe : effects) {
            if (pe == null) continue;
            PotionEffectType type = pe.getType();
            if (type == null) continue;

            // Preferred: modern API
            if (IS_BENEFICIAL_METHOD != null) {
                try {
                    Object v = IS_BENEFICIAL_METHOD.invoke(type);
                    if (v instanceof Boolean b) {
                        if (!b) return false; // any harmful -> not all beneficial
                        continue;
                    }
                } catch (Throwable ignored) {
                    // fall through
                }
            }

            // Fallback: known harmful/negative effects
            if (isKnownHarmful(type)) return false;
        }

        return true;
    }

    private static Method resolveIsBeneficialMethod() {
        try {
            return PotionEffectType.class.getMethod("isBeneficial");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveEffectTypeGetKeyMethod() {
        try {
            return PotionEffectType.class.getMethod("getKey");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isKnownHarmful(PotionEffectType t) {
        String key = effectKey(t);

        // Common negative effects (covers thrown “aggressive” potions)
        return key.equals("instant_damage")
                || key.equals("poison")
                || key.equals("wither")
                || key.equals("slowness")
                || key.equals("weakness")
                || key.equals("blindness")
                || key.equals("hunger")
                || key.equals("nausea")          // some servers show "nausea"
                || key.equals("confusion")       // older naming
                || key.equals("levitation")
                || key.equals("darkness")
                || key.equals("bad_omen")
                || key.equals("unluck")
                || key.equals("mining_fatigue")  // modern key
                || key.equals("slow_digging");   // older naming
    }

    /**
     * Zero compile errors across API variants:
     * - Avoids Registry.EFFECT (varies by Bukkit versions)
     * - Avoids direct PotionEffectType#getKey call (deprecated in some versions)
     * - Uses reflection to call getKey() if present, then reads NamespacedKey#getKey()
     */
    private String effectKey(PotionEffectType t) {
        if (t == null) return "";

        // Try PotionEffectType#getKey() via reflection
        if (EFFECTTYPE_GETKEY_METHOD != null) {
            try {
                Object v = EFFECTTYPE_GETKEY_METHOD.invoke(t);
                if (v instanceof NamespacedKey nk) {
                    return nk.getKey().toLowerCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {}
        }

        // Fallback
        return t.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the cloud's base potion name across API versions:
     * - Some APIs have getBasePotionType(): PotionType
     * - Others have getBasePotionData(): PotionData (then .getType())
     */
    private String getCloudBasePotionName(AreaEffectCloud cloud) {
        if (cloud == null) return null;

        // Try getBasePotionType()
        try {
            Method m = cloud.getClass().getMethod("getBasePotionType");
            Object v = m.invoke(cloud);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}

        // Try getBasePotionData().getType()
        try {
            Method m = cloud.getClass().getMethod("getBasePotionData");
            Object data = m.invoke(cloud);
            if (data != null) {
                Method mt = data.getClass().getMethod("getType");
                Object type = mt.invoke(data);
                if (type != null) return type.toString();
            }
        } catch (Throwable ignored) {}

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
