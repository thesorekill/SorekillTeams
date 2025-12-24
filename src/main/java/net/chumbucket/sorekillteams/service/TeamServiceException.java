/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.service;

import java.util.Arrays;
import java.util.Objects;

/**
 * Typed service exception for stable command handling.
 *
 * Carries:
 * - a stable error code ({@link TeamError})
 * - an optional messages.yml key
 * - optional placeholder pairs (e.g., "{seconds}", "10")
 */
public final class TeamServiceException extends RuntimeException {

    private final TeamError code;
    private final String messageKey; // optional
    private final String[] pairs;    // optional placeholder pairs

    public TeamServiceException(TeamError code) {
        this(code, null);
    }

    public TeamServiceException(TeamError code, String messageKey, String... pairs) {
        super(code == null ? "UNKNOWN" : code.name());
        this.code = Objects.requireNonNull(code, "code");
        this.messageKey = (messageKey == null || messageKey.isBlank()) ? null : messageKey;
        this.pairs = (pairs == null) ? new String[0] : pairs.clone();
    }

    public TeamError code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns placeholder pairs as a defensive copy.
     */
    public String[] pairs() {
        return pairs.clone();
    }

    @Override
    public String toString() {
        return "TeamServiceException{" +
                "code=" + code +
                ", messageKey='" + messageKey + '\'' +
                ", pairs=" + Arrays.toString(pairs) +
                '}';
    }
}
