# SorekillTeams

**SorekillTeams** is a lightweight teams plugin for **Paper and Spigot** that provides clean team management, private team chat, invite-based membership, and friendly-fire control.

Designed for modern servers and future proxy networks, SorekillTeams focuses on core team mechanics without bloated clan systems or unnecessary complexity.

## Overview

SorekillTeams allows players to form persistent teams that feel natural and intuitive to play with.

Players can team up with friends, communicate privately, manage invitations, and avoid accidental PvP — all while server owners retain full control through permissions and configuration.

The plugin is built with future **Velocity and BungeeCord** support in mind and follows clean architecture principles to scale cleanly beyond a single server.

## Features

- ⭐ **Team creation & management** — Create, invite, accept, deny, leave, disband, and view team info.
- ⭐ **Team invites system** — Expiring, cooldown-based invites with list and accept/deny support.
- ⭐ **Team chat** — Private team-only chat via `/tc` or toggleable team chat mode.
- ⭐ **Friendly-fire control** — Toggle whether teammates can damage each other (including projectiles).
- ⭐ **Paper & Spigot compatible** — Built against the Spigot API; runs cleanly on Paper and forks.
- ⭐ **Network-ready design** — Stores server identifiers to support future proxy expansion.
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
4. Grant permissions using a permissions plugin (e.g. LuckPerms).
5. Configure settings in `config.yml` and `messages.yml` as needed.

## Commands

| Command | Usage | Description | Permission |
|--------|-------|------------|------------|
| `/team` | `/team help` | Base team command | `sorekillteams.use` |
| `/team create` | `/team create <name>` | Create a new team | `sorekillteams.create` |
| `/team invite` | `/team invite <player>` | Invite a player to your team | `sorekillteams.invite` |
| `/team invites` | `/team invites` | View pending invites | `sorekillteams.invites` |
| `/team accept` | `/team accept <team>` | Accept a team invite | `sorekillteams.accept` |
| `/team deny` | `/team deny <team>` | Deny a team invite | `sorekillteams.deny` |
| `/team leave` | `/team leave` | Leave your team | `sorekillteams.leave` |
| `/team disband` | `/team disband` | Disband your team (owner only) | `sorekillteams.disband` |
| `/team info` | `/team info` | View team information | `sorekillteams.info` |
| `/team ff` | `/team ff <on|off|toggle|status>` | Manage friendly fire | `sorekillteams.ff` |
| `/tc` | `/tc [message]` | Team chat (toggle or send) | `sorekillteams.chat` |
| `/sorekillteams reload` | `/sorekillteams reload` | Reload configuration | `sorekillteams.reload` |
| `/sorekillteams version` | `/sorekillteams version` | View plugin version | `sorekillteams.version` |

## Permissions

All permissions default to **false** and must be explicitly granted using a permissions plugin such as **LuckPerms**.

| Permission | Description |
|-----------|------------|
| `sorekillteams.use` | Use `/team` |
| `sorekillteams.info` | View team info |
| `sorekillteams.create` | Create teams |
| `sorekillteams.invite` | Invite players |
| `sorekillteams.invites` | View received invites |
| `sorekillteams.accept` | Accept team invites |
| `sorekillteams.deny` | Deny team invites |
| `sorekillteams.leave` | Leave a team |
| `sorekillteams.disband` | Disband a team |
| `sorekillteams.chat` | Use team chat |
| `sorekillteams.ff` | Manage team friendly fire |
| `sorekillteams.admin` | Use admin commands |
| `sorekillteams.reload` | Reload plugin configuration |
| `sorekillteams.version` | View version info |
| `sorekillteams.*` | Grants all SorekillTeams permissions |

## Placeholders

SorekillTeams includes optional support for **PlaceholderAPI** and **MiniPlaceholders**.

When enabled, placeholders can be used in scoreboards, chat formats, and other supported plugins.

(Placeholder documentation will be expanded in future releases.)

## Configuration

SorekillTeams uses two configuration files:

- `config.yml` — gameplay rules, limits, invites, chat, friendly fire, integrations
- `messages.yml` — all user-facing messages and prefixes

All configuration options include safe defaults and can be reloaded using `/sorekillteams reload`.

## Planned Features

The following features are planned or under consideration for future releases:

- Proxy support (Velocity & BungeeCord)
- Cross-server team features
- Team roles (owner, moderator, member)
- Team GUI menus
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
- Built for use on the **Chumbucket Network** — chumbucket.net

© 2025 Sorekill. Licensed under the Apache-2.0 License.
