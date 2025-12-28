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
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Player head builder that supports offline skins on Paper via reflection.
 *
 * Key behavior:
 * - Uses Paper PlayerProfile factories when available (createPlayerProfile/createProfile).
 * - Fetches textures async using PlayerProfile#update() when available; falls back to complete().
 * - IMPORTANT FIX: Only "trust" Paper profiles if they actually contain a skin texture.
 *   If Paper returns a profile without textures, we fall back to SkullMeta#setOwningPlayer
 *   (which uses the server's cached skin data and is why it "worked before refactor").
 * - Supports refresh callbacks so non-cycling menus can update when textures arrive.
 */
public final class MenuSkullFactory {

    private static final Method BUKKIT_CREATE_PROFILE_UUID =
            resolveBukkitProfileFactory("createProfile", UUID.class);
    private static final Method BUKKIT_CREATE_PROFILE_UUID_NAME =
            resolveBukkitProfileFactory("createProfile", UUID.class, String.class);

    private static final Method BUKKIT_CREATE_PLAYER_PROFILE_UUID =
            resolveBukkitProfileFactory("createPlayerProfile", UUID.class);
    private static final Method BUKKIT_CREATE_PLAYER_PROFILE_UUID_NAME =
            resolveBukkitProfileFactory("createPlayerProfile", UUID.class, String.class);

    private static final Method SKULL_SET_PROFILE = resolveSkullSetProfile();
    private static final Method PLAYER_GET_PROFILE = resolvePlayerGetProfile();

    private final SorekillTeamsPlugin plugin;
    private final MenuText text;

    // uuid -> completed PlayerProfile object (Paper only)
    private final Map<UUID, Object> profileCache = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    // uuid -> callbacks to run once completed (sync on main thread)
    private final Map<UUID, List<Runnable>> refreshCallbacks = new ConcurrentHashMap<>();

    public MenuSkullFactory(SorekillTeamsPlugin plugin, MenuText text) {
        this.plugin = plugin;
        this.text = text;
    }

    /**
     * Optional: call on PlayerJoinEvent to cache a known-good online profile (with textures).
     * This helps a ton if Mojang fetch fails or if skins should still render for "offline" players.
     */
    public void cacheOnlineProfile(Player p) {
        if (p == null) return;
        if (PLAYER_GET_PROFILE == null) return;

        try {
            Object prof = PLAYER_GET_PROFILE.invoke(p);
            if (prof != null && profileHasSkin(prof)) {
                profileCache.put(p.getUniqueId(), prof);
                runRefreshCallbacks(p.getUniqueId());
            }
        } catch (Throwable ignored) {
        }
    }

    public ItemStack buildMemberCycleHead(Player viewer, Team team, ConfigurationSection mh, int idx) {
        List<UUID> members = text.uniqueTeamMembers(team);
        if (members.isEmpty()) return new ItemStack(Material.PLAYER_HEAD);

        UUID who = members.get(Math.floorMod(idx, members.size()));

        String name = text.apply(viewer, team, plugin.menus().str(mh, "name", "&bMembers"));
        List<String> lore = text.applyList(viewer, team, plugin.menus().strList(mh, "lore"));

        // cycles anyway; callback not required
        return buildPlayerHead(who, name, lore, null);
    }

    public ItemStack buildBrowseTeamCycleHead(Player viewer,
                                             Team team,
                                             int idx,
                                             String nameTemplate,
                                             List<String> loreTemplate,
                                             String ownerName,
                                             String membersCount) {

        List<UUID> members = text.uniqueTeamMembers(team);
        UUID who = members.isEmpty() ? team.getOwner() : members.get(Math.floorMod(idx, members.size()));

        String teamName = (team.getName() == null ? "Team" : team.getName());
        String builtName = Msg.color(nameTemplate.replace("{team}", Msg.color(teamName)));

        List<String> builtLore = loreTemplate.stream()
                .map(x -> Msg.color(
                        x.replace("{team}", Msg.color(teamName))
                                .replace("{owner}", ownerName)
                                .replace("{members}", membersCount)
                ))
                .toList();

        // cycles anyway; callback not required
        return buildPlayerHead(who, builtName, builtLore, null);
    }

    public ItemStack buildPlayerHead(UUID owningUuid, String name, List<String> lore) {
        return buildPlayerHead(owningUuid, name, lore, null);
    }

    public ItemStack buildPlayerHead(UUID owningUuid, String name, List<String> lore, Runnable refreshWhenReady) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();

        if (!(meta instanceof SkullMeta skull)) {
            return MenuItems.item(Material.PLAYER_HEAD, name, lore);
        }

        boolean paperAvailable = owningUuid != null && SKULL_SET_PROFILE != null && hasAnyProfileFactory();

        boolean appliedPaperProfileWithSkin = false;

        if (paperAvailable) {
            Object cached = profileCache.get(owningUuid);
            if (cached != null) {
                // ✅ Only count Paper as "applied" if the cached profile actually has a skin
                boolean applied = applyProfile(skull, cached);
                appliedPaperProfileWithSkin = applied && profileHasSkin(cached);
            } else {
                if (refreshWhenReady != null) {
                    refreshCallbacks.compute(owningUuid, (k, v) -> {
                        if (v == null) v = new ArrayList<>();
                        v.add(refreshWhenReady);
                        return v;
                    });
                }

                String hint;
                try {
                    hint = text.nameOf(owningUuid);
                } catch (Throwable ignored) {
                    hint = null;
                }
                warmProfileAsync(owningUuid, hint);
            }
        }

        // ✅ Critical fix: if Paper didn't apply a textured profile, fall back to Spigot cache behavior.
        // This is what made it "work before refactor" for players the server already knows.
        if (!appliedPaperProfileWithSkin && owningUuid != null) {
            try {
                OfflinePlayer off = Bukkit.getOfflinePlayer(owningUuid);
                if (off != null) skull.setOwningPlayer(off);
            } catch (Throwable ignored) {
            }
        }

        skull.setDisplayName(Msg.color(name == null ? "" : name));
        if (lore != null && !lore.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String line : lore) out.add(Msg.color(line));
            skull.setLore(out);
        }
        skull.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(skull);
        return it;
    }

    // --------------------
    // Async profile warming (Paper)
    // --------------------

    private void warmProfileAsync(UUID uuid, String nameHint) {
        if (plugin == null || uuid == null) return;

        if (profileCache.containsKey(uuid)) {
            runRefreshCallbacks(uuid);
            return;
        }
        if (!inFlight.add(uuid)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean cachedNow = false;
            try {
                Object profile = createProfile(uuid, nameHint);
                if (profile == null) return;

                // ✅ Prefer update() if present, but only cache if it actually has a skin.
                Object updated = tryUpdateReturningProfile(profile);
                if (updated != null && profileHasSkin(updated)) {
                    profileCache.put(uuid, updated);
                    cachedNow = true;
                } else {
                    // fallback to complete(), again only cache if skin exists
                    if (tryComplete(profile) && profileHasSkin(profile)) {
                        profileCache.put(uuid, profile);
                        cachedNow = true;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                inFlight.remove(uuid);
                if (cachedNow || profileCache.containsKey(uuid)) {
                    runRefreshCallbacks(uuid);
                }
            }
        });
    }

    private Object createProfile(UUID uuid, String nameHint) {
        Object p = null;

        if (p == null && BUKKIT_CREATE_PLAYER_PROFILE_UUID_NAME != null) {
            try {
                p = BUKKIT_CREATE_PLAYER_PROFILE_UUID_NAME.invoke(null, uuid, nameHint);
            } catch (Throwable ignored) {
            }
        }
        if (p == null && BUKKIT_CREATE_PLAYER_PROFILE_UUID != null) {
            try {
                p = BUKKIT_CREATE_PLAYER_PROFILE_UUID.invoke(null, uuid);
            } catch (Throwable ignored) {
            }
        }

        if (p == null && BUKKIT_CREATE_PROFILE_UUID_NAME != null) {
            try {
                p = BUKKIT_CREATE_PROFILE_UUID_NAME.invoke(null, uuid, nameHint);
            } catch (Throwable ignored) {
            }
        }
        if (p == null && BUKKIT_CREATE_PROFILE_UUID != null) {
            try {
                p = BUKKIT_CREATE_PROFILE_UUID.invoke(null, uuid);
            } catch (Throwable ignored) {
            }
        }

        return p;
    }

    /**
     * Newer Paper: PlayerProfile#update() returns CompletableFuture<PlayerProfile>.
     * We return the resulting profile (may be same object), or null if unsupported/failed.
     */
    private Object tryUpdateReturningProfile(Object profile) {
        if (profile == null) return null;

        try {
            Method m = profile.getClass().getMethod("update");
            Object r = m.invoke(profile);
            if (!(r instanceof CompletableFuture<?> cf)) return null;

            // Wait a bit for Mojang fetch. If it times out, caller will fall back to complete().
            Object done = cf.get(5, TimeUnit.SECONDS);
            return done; // may be PlayerProfile impl
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean tryComplete(Object profile) {
        if (profile == null) return false;

        try {
            try {
                Method m = profile.getClass().getMethod("complete", boolean.class);
                Object r = m.invoke(profile, true);
                return (r instanceof Boolean) ? (Boolean) r : true;
            } catch (NoSuchMethodException ignored) {
                Method m = profile.getClass().getMethod("complete");
                Object r = m.invoke(profile);
                return (r instanceof Boolean) ? (Boolean) r : true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean applyProfile(SkullMeta skull, Object profile) {
        if (skull == null || profile == null || SKULL_SET_PROFILE == null) return false;
        try {
            SKULL_SET_PROFILE.invoke(skull, profile);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ✅ The important new guard:
     * Only treat a Paper profile as usable if it actually has a skin texture.
     */
    private boolean profileHasSkin(Object profile) {
        if (profile == null) return false;

        try {
            // PlayerProfile#getTextures()
            Method getTextures = profile.getClass().getMethod("getTextures");
            Object textures = getTextures.invoke(profile);
            if (textures == null) return false;

            // Common Paper: PlayerTextures#getSkin() -> URL (nullable)
            try {
                Method getSkin = textures.getClass().getMethod("getSkin");
                Object skin = getSkin.invoke(textures);
                return skin != null;
            } catch (NoSuchMethodException ignored) {
            }

            // Some impls: isEmpty()
            try {
                Method isEmpty = textures.getClass().getMethod("isEmpty");
                Object r = isEmpty.invoke(textures);
                if (r instanceof Boolean b) return !b;
            } catch (NoSuchMethodException ignored) {
            }

            // Last resort heuristic
            String s = String.valueOf(textures);
            return s.toLowerCase(Locale.ROOT).contains("skin");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runRefreshCallbacks(UUID uuid) {
        if (uuid == null || plugin == null) return;

        List<Runnable> cbs = refreshCallbacks.remove(uuid);
        if (cbs == null || cbs.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Runnable r : cbs) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // --------------------
    // Reflection helpers
    // --------------------

    private static boolean hasAnyProfileFactory() {
        return BUKKIT_CREATE_PROFILE_UUID != null
                || BUKKIT_CREATE_PROFILE_UUID_NAME != null
                || BUKKIT_CREATE_PLAYER_PROFILE_UUID != null
                || BUKKIT_CREATE_PLAYER_PROFILE_UUID_NAME != null;
    }

    private static Method resolveBukkitProfileFactory(String name, Class<?>... params) {
        try {
            return Bukkit.class.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveSkullSetProfile() {
        try {
            for (Method m : SkullMeta.class.getMethods()) {
                String mn = m.getName();
                if (!mn.equals("setPlayerProfile") && !mn.equals("setOwnerProfile")) continue;

                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;

                String typeName = params[0].getName();
                if (typeName.equals("org.bukkit.profile.PlayerProfile")
                        || typeName.equals("com.destroystokyo.paper.profile.PlayerProfile")
                        || typeName.endsWith("PlayerProfile")) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method resolvePlayerGetProfile() {
        try {
            // Paper: public PlayerProfile getPlayerProfile()
            return Player.class.getMethod("getPlayerProfile");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
