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

    /**
     * @return true if {@code latest} represents a strictly newer version than {@code current}
     *
     * Compares up to 3 numeric components: major.minor.patch
     * Supports inputs like:
     * - "1", "1.2", "1.2.3"
     * - "v1.2.3"
     * - "1.2.3-SNAPSHOT", "1.2.3+build.7"
     * - "  1.2.3  "
     */
    public static boolean isNewer(String latest, String current) {
        int[] a = parseSemver(latest);
        int[] b = parseSemver(current);

        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) return true;
            if (a[i] < b[i]) return false;
        }
        return false; // equal
    }

    /**
     * Parse "semver-ish" version into [major, minor, patch].
     * Non-numeric suffixes are ignored ("-SNAPSHOT", "+build", etc).
     * Missing components default to 0.
     */
    private static int[] parseSemver(String v) {
        int[] out = new int[]{0, 0, 0};
        if (v == null) return out;

        String base = v.trim();
        if (base.isEmpty()) return out;

        // Strip leading 'v' (v1.2.3)
        if (base.length() > 1 && (base.charAt(0) == 'v' || base.charAt(0) == 'V')) {
            base = base.substring(1).trim();
        }

        // Stop at first prerelease/build separator (- or +)
        int cutDash = base.indexOf('-');
        int cutPlus = base.indexOf('+');
        int cut = -1;
        if (cutDash >= 0 && cutPlus >= 0) cut = Math.min(cutDash, cutPlus);
        else cut = Math.max(cutDash, cutPlus);

        if (cut >= 0) base = base.substring(0, cut);

        // Split into dot components
        String[] parts = base.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            out[i] = parseLeadingInt(parts[i]);
        }

        return out;
    }

    /**
     * Parses the leading integer of a token safely.
     * Examples:
     * - "12" -> 12
     * - "12rc1" -> 12
     * - "rc1" -> 0
     */
    private static int parseLeadingInt(String token) {
        if (token == null) return 0;

        String t = token.trim();
        if (t.isEmpty()) return 0;

        int n = 0;
        boolean sawDigit = false;

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                sawDigit = true;

                int digit = (c - '0');
                // clamp on overflow
                if (n > (Integer.MAX_VALUE - digit) / 10) return Integer.MAX_VALUE;
                n = (n * 10) + digit;
            } else {
                // stop at first non-digit after digits begin
                if (sawDigit) break;
                // if we haven't started digits yet, keep scanning (handles "v1", though we strip v earlier)
            }
        }

        return sawDigit ? n : 0;
    }
}
