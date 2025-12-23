/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.model;

import java.util.UUID;

public record TeamInvite(UUID teamId, UUID inviter, long expiresAtMs) {
    public boolean expired(long nowMs) { return nowMs > expiresAtMs; }
}
