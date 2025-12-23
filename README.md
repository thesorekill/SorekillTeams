# SorekillTeams

**SorekillTeams** is a lightweight teams plugin for **Paper and Spigot** that provides clean team management, private team chat, friendly-fire protection, and shared team homes.

Designed for modern servers and future proxy networks, SorekillTeams focuses on core team mechanics without bloated clan systems or unnecessary complexity.

## Overview

SorekillTeams allows players to form persistent teams that feel natural and intuitive to play with.

Players can team up with friends, communicate privately, avoid accidental PvP, and share team homes — all while server owners retain full control through permissions and configuration.

The plugin is built with future **Velocity and BungeeCord** support in mind and follows clean architecture principles to scale beyond a single server.

## Features

- ⭐ **Team creation & management** — Create, invite, join, leave, and disband teams using simple commands.
- ⭐ **Team chat** — Private team-only chat via `/tc` or toggleable team chat mode.
- ⭐ **Friendly-fire protection** — Prevent teammates from damaging each other (including projectiles).
- ⭐ **Team homes** — Shared homes that all team members can teleport to.
- ⭐ **Paper & Spigot compatible** — Built against the Spigot API; runs cleanly on Paper and forks.
- ⭐ **Network-ready design** — Stores server identifiers to support future proxy teleporting.
- ⭐ **Optional placeholder support** — Integrates with PlaceholderAPI and MiniPlaceholders.
- ⭐ **Lightweight & focused** — No economy, claims, or RPG mechanics — just teams.

## Requirements

- Java 21
- Paper or Spigot 1.21.x (or compatible forks)
- (Optional) PlaceholderAPI 2.11.0+
- (Optional) MiniPlaceholders 3.0+

## Installation

1. Download `SorekillTeams.jar`.
2. Place the jar into your server’s `plugins/` directory.
3. Start or restart the server.
4. Configure settings in `config.yml` and `messages.yml` as needed.

## Commands

| Command | Usage | Description | Permission |
|--------|-------|------------|------------|
| `/team` | `/team help` | Base team command | `sorekillteams.use` |
| `/team create` | `/team create <name>` | Create a new team | `sorekillteams.create` |
| `/team invite` | `/team invite <player>` | Invite a player to your team | `sorekillteams.invite` |
| `/team accept` | `/team accept` | Accept a pending invite | `sorekillteams.accept` |
| `/team leave` | `/team leave` | Leave your team | `sorekillteams.leave` |
| `/team disband` | `/team disband` | Disband your team | `sorekillteams.disband` |
| `/team sethome` | `/team sethome <name>` | Set a team home | `sorekillteams.sethome` |
| `/team home` | `/team home <name>` | Teleport to a team home | `sorekillteams.home` |
| `/team delhome` | `/team delhome <name>` | Delete a team home | `sorekillteams.delhome` |
| `/tc` | `/tc <message>` | Send a team chat message | `sorekillteams.chat` |
| `/sorekillteams reload` | `/sorekillteams reload` | Reload the plugin | `sorekillteams.reload` |
| `/sorekillteams version` | `/sorekillteams version` | View plugin version | `sorekillteams.version` |

## Permissions

All permissions default to **false** unless otherwise noted and are intended for use with permission plugins such as **LuckPerms**.

| Permission | Description | Default |
|-----------|------------|---------|
| `sorekillteams.use` | Use `/team` | true |
| `sorekillteams.create` | Create teams | true |
| `sorekillteams.invite` | Invite players | true |
| `sorekillteams.accept` | Accept team invites | true |
| `sorekillteams.leave` | Leave a team | true |
| `sorekillteams.disband` | Disband a team | op |
| `sorekillteams.kick` | Kick team members | op |
| `sorekillteams.chat` | Use team chat | true |
| `sorekillteams.sethome` | Set team homes | true |
| `sorekillteams.home` | Teleport to team homes | true |
| `sorekillteams.delhome` | Delete team homes | true |
| `sorekillteams.reload` | Reload the plugin | op |
| `sorekillteams.version` | View version info | true |
| `sorekillteams.max_members.<n>` | Set max team size | false |

> **Note:** Team size limits can later be controlled via permission-based limits.

## Placeholders

SorekillTeams includes optional support for **PlaceholderAPI** and **MiniPlaceholders**.

When enabled, placeholders can be used in scoreboards, chat formats, and other supported plugins.

(Placeholder list will be documented once implemented.)

## Configuration

SorekillTeams uses two configuration files:

- `config.yml` — gameplay rules, limits, integrations
- `messages.yml` — all user-facing messages and prefixes

All configuration options include safe defaults and can be reloaded using `/sorekillteams reload`.

## Planned Features

The following features are planned or under consideration for future releases:

- Proxy support (Velocity & BungeeCord)
- Cross-server team homes
- Team roles (owner, admin, member)
- Team information GUI
- Scoreboard and nametag integrations

Planned features are subject to change and will be implemented with a focus on stability and network compatibility.

## License

SorekillTeams is licensed under the **Apache License, Version 2.0**.  
See the `LICENSE` file for full license details.

## Contributing

Bug reports, suggestions, and pull requests are welcome.  
Please keep contributions focused and aligned with the plugin’s lightweight design goals.

## Credits

- Developed by **Sorekill**
- Built for use on the **Chumbucket Network** chumbucket.net

© 2025 Sorekill. Licensed under the Apache-2.0 License.
