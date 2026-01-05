/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.network;

public interface InviteBus {

    void start();

    void stop();

    boolean isRunning();

    void publish(InvitePacket pkt);

    void publish(InviteTogglePacket pkt);
}
