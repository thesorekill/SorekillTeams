/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillteams.command;

import org.bukkit.entity.Player;

public interface TeamSubcommandModule {
    /**
     * @return true if this module handled the command (even if it showed an error/usage),
     *         false if it does not apply to this subcommand.
     */
    boolean handle(Player player, String sub, String[] args, boolean debug);
}
