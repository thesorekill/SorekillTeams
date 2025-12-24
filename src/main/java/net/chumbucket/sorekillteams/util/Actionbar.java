/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.util;

import net.chumbucket.sorekillteams.SorekillTeamsPlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Actionbar {

    private final SorekillTeamsPlugin plugin;

    // Optional per-player cooldown
    private final Map<UUID, Long> lastSentMs = new ConcurrentHashMap<>();

    // Paper: Player#sendActionBar(net.kyori.adventure.text.Component)
    private static final Method PAPER_SEND_ACTIONBAR = resolvePaperSendActionbar();
    // Adventure: LegacyComponentSerializer.legacyAmpersand().deserialize(String)
    private static final Object ADVENTURE_AMP_SERIALIZER = resolveAdventureAmpSerializer();
    private static final Method ADVENTURE_DESERIALIZE = resolveAdventureDeserializeMethod(ADVENTURE_AMP_SERIALIZER);

    public Actionbar(SorekillTeamsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Send an actionbar message from messages.yml.
     *
     * Behavior you wanted:
     * - If the message is missing OR equals "" (blank) in messages.yml -> do NOT send anything.
     * - Prefix is NOT forced for actionbar unless the message itself includes {prefix}.
     */
    public void send(Player player, String key, String... pairs) {
        if (player == null || key == null || key.isBlank()) return;

        // IMPORTANT: use raw lookup so "" truly disables sending
        String raw = plugin.msg().raw(key);
        if (raw == null || raw.isBlank()) return;

        String built = applyPairs(raw, pairs);

        // Do NOT auto-prefix actionbars; only expand if the string includes it.
        if (built.contains("{prefix}")) {
            built = built.replace("{prefix}", plugin.msg().prefix());
        }

        // Colorize & codes (and allow § if present)
        String colored = Msg.color(built);
        if (colored.isBlank()) return;

        sendColored(player, colored);
    }

    /**
     * Same as send(), but with a per-player cooldown (ms).
     */
    public void send(Player player, String key, long cooldownMs, String... pairs) {
        if (player == null || key == null || key.isBlank()) return;

        if (cooldownMs > 0) {
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            long last = lastSentMs.getOrDefault(id, 0L);
            if (now - last < cooldownMs) return;
            lastSentMs.put(id, now);
        }

        send(player, key, pairs);
    }

    /**
     * Send a pre-built string to the actionbar.
     *
     * NOTE: This method intentionally does NOT auto-prefix.
     * If you want prefix, include it in the string yourself.
     *
     * Passing "" clears the actionbar.
     */
    public void sendRaw(Player player, String legacyWithAmpOrSection) {
        if (player == null) return;

        // Clearing is allowed
        if (legacyWithAmpOrSection == null || legacyWithAmpOrSection.isEmpty()) {
            sendColored(player, "");
            return;
        }

        String colored = Msg.color(legacyWithAmpOrSection);
        sendColored(player, colored);
    }

    public void clear(Player player) {
        if (player == null) return;
        lastSentMs.remove(player.getUniqueId());
        sendColored(player, "");
    }

    public void clearAll() {
        lastSentMs.clear();
    }

    // ============================================================
    // Internals
    // ============================================================

    private void sendColored(Player player, String sectionColored) {
        if (player == null) return;

        // Paper/Adventure path (preferred; no Bungee deprecation)
        if (PAPER_SEND_ACTIONBAR != null && ADVENTURE_AMP_SERIALIZER != null && ADVENTURE_DESERIALIZE != null) {
            try {
                Object component = ADVENTURE_DESERIALIZE.invoke(ADVENTURE_AMP_SERIALIZER, sectionColored);
                if (component != null) {
                    PAPER_SEND_ACTIONBAR.invoke(player, component);
                    return;
                }
            } catch (Throwable ignored) {
                // fall through
            }
        }

        // Spigot fallback (works everywhere; may show API deprecation warnings depending on environment)
        try {
            player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(sectionColored)
            );
        } catch (Throwable ignored) {
            // If even this fails for some reason, just do nothing.
        }
    }

    private static String applyPairs(String input, String... pairs) {
        if (input == null || input.isEmpty()) return "";
        String out = input;

        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                String from = (pairs[i] == null) ? "" : pairs[i];
                String to = (pairs[i + 1] == null) ? "" : pairs[i + 1];
                if (!from.isEmpty()) out = out.replace(from, to);
            }
        }
        return out;
    }

    private static Method resolvePaperSendActionbar() {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            return Player.class.getMethod("sendActionBar", componentClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolveAdventureAmpSerializer() {
        try {
            // LegacyComponentSerializer.legacyAmpersand()
            Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            Method legacyAmpersand = serializerClass.getMethod("legacyAmpersand");
            return legacyAmpersand.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveAdventureDeserializeMethod(Object serializer) {
        if (serializer == null) return null;
        try {
            return serializer.getClass().getMethod("deserialize", String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
