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

import java.util.UUID;

/** Small UUID parsing helper (same as your safeUuid()). */
public final class MenuIds {

    private MenuIds() {}

    public static UUID safeUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception ignored) { return null; }
    }
}
