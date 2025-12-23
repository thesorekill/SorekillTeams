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

import java.util.Objects;

/**
 * Typed service exception for stable command handling.
 * Carries:
 * - a stable error code (TeamError)
 * - optional messages.yml key + placeholder pairs for user-facing output
 */
public final class TeamServiceException extends RuntimeException {

    private final TeamError code;
    private final String messageKey;     // optional
    private final String[] pairs;        // optional

    public TeamServiceException(TeamError code) {
        super(code.name());
        this.code = Objects.requireNonNull(code);
        this.messageKey = null;
        this.pairs = new String[0];
    }

    public TeamServiceException(TeamError code, String messageKey, String... pairs) {
        super(code.name());
        this.code = Objects.requireNonNull(code);
        this.messageKey = messageKey;
        this.pairs = pairs == null ? new String[0] : pairs;
    }

    public TeamError code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    public String[] pairs() {
        return pairs;
    }
}
