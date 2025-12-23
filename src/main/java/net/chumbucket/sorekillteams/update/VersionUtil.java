/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.update;

public final class VersionUtil {

    private VersionUtil() {}

    // returns true if latest > current
    public static boolean isNewer(String latest, String current) {
        int[] a = parseSemver(latest);
        int[] b = parseSemver(current);

        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) return true;
            if (a[i] < b[i]) return false;
        }
        return false; // equal
    }

    private static int[] parseSemver(String v) {
        // supports "1.0.1", "1.0", "1", "1.0.1-SNAPSHOT"
        int[] out = new int[]{0, 0, 0};
        if (v == null) return out;

        String base = v.trim();
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);

        String[] parts = base.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
