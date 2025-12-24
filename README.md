# SorekillTeams

**SorekillTeams** is a lightweight teams plugin for **Paper and Spigot** that provides clean team management, private team chat, invite-based membership, homes, and configurable friendly-fire protection.

Designed for modern servers and future proxy networks, SorekillTeams focuses on **core team mechanics** without bloated clan systems, claims, or RPG features.

---

## Overview

SorekillTeams allows players to form persistent teams that feel natural and intuitive.

Players can:
- Create and manage teams
- Invite and accept members
- Chat privately with teammates
- Set and teleport to team homes
- Prevent accidental PvP with friendly-fire protection

Server owners retain **full control** through permissions and a detailed configuration system, with safety-focused defaults and future-proofed architecture.

---

## Features

- ⭐ **Team creation & management**
  - Create, rename, transfer ownership, kick members, leave, or disband teams
- ⭐ **Advanced invite system**
  - Expiring invites, cooldowns, invite caps, re-invite refresh support
- ⭐ **Team chat**
  - `/tc` command or toggle mode
  - Admin spy support with separate formatting
- ⭐ **Friendly-fire control**
  - Block teammate damage (melee, projectiles, potions, explosions, tridents)
  - Per-team toggle + admin bypass
- ⭐ **Team homes**
  - Set and teleport to team homes
  - Warmups, cooldowns, and max-home limits
  - Network-aware metadata for future proxy support
- ⭐ **Update checker**
  - GitHub-based update detection
  - Optional in-game OP notifications
- ⭐ **Safe storage system**
  - Autosave, atomic writes, and rolling backups
- ⭐ **Paper & Spigot compatible**
- ⭐ **Optional placeholder support**
  - PlaceholderAPI & MiniPlaceholders
- ⭐ **Lightweight & focused**
  - No economy, claims, or RPG systems

---

## Requirements

- **Java 21**
- **Paper or Spigot 1.21.x**
- (Optional) PlaceholderAPI 2.11.0+
- (Optional) MiniPlaceholders 3.0+

---

## Installation

1. Download `SorekillTeams.jar`
2. Place it in your server’s `plugins/` directory
3. Start or restart the server
4. Configure permissions (LuckPerms recommended)
5. Adjust `config.yml` and `messages.yml` as needed

Reload configs anytime with:
'/sorekillteams reload'

---

## Commands

| Command | Usage | Description | Permission |
|------|------|------------|------------|
| `/team` | `/team` | Base team command | `sorekillteams.use` |
| `/team create` | `/team create <name>` | Create a team | `sorekillteams.create` |
| `/team invite` | `/team invite <player>` | Invite a player | `sorekillteams.invite` |
| `/team accept` | `/team accept [team]` | Accept an invite | `sorekillteams.accept` |
| `/team deny` | `/team deny [team]` | Deny an invite | `sorekillteams.deny` |
| `/team leave` | `/team leave` | Leave your team | `sorekillteams.leave` |
| `/team disband` | `/team disband` | Disband your team | `sorekillteams.disband` |
| `/team info` | `/team info` | View team info | `sorekillteams.info` |
| `/team kick` | `/team kick <player>` | Kick a member | `sorekillteams.kick` |
| `/team transfer` | `/team transfer <player>` | Transfer ownership | `sorekillteams.transfer` |
| `/team rename` | `/team rename <name>` | Rename your team | `sorekillteams.rename` |
| `/team ff` | `/team ff <on\|off\|toggle>` | Toggle friendly fire | `sorekillteams.ff` |
| `/tc` | `/tc [message]` | Team chat or toggle mode | `sorekillteams.teamchat` |
| `/sorekillteams reload` | `/sorekillteams reload` | Reload config/messages | `sorekillteams.admin.reload` |
| `/sorekillteams version` | `/sorekillteams version` | Show plugin version | `sorekillteams.admin` |

---

## Permissions

### Base
| Permission | Description | Default |
|-----------|------------|---------|
| `sorekillteams.use` | Use `/team` | true |
| `sorekillteams.teamchat` | Use team chat | true |
| `sorekillteams.spy` | Spy on team chat | op |
| `sorekillteams.friendlyfire.bypass` | Bypass FF protection | op |

### Team Management
| Permission | Description |
|-----------|------------|
| `sorekillteams.create` | Create teams |
| `sorekillteams.invite` | Invite players |
| `sorekillteams.accept` | Accept invites |
| `sorekillteams.deny` | Deny invites |
| `sorekillteams.leave` | Leave team |
| `sorekillteams.disband` | Disband team |
| `sorekillteams.kick` | Kick members |
| `sorekillteams.transfer` | Transfer ownership |
| `sorekillteams.rename` | Rename team |
| `sorekillteams.ff` | Toggle friendly fire |

### Max Team Size
- `sorekillteams.max.<number>`
  - Example: `sorekillteams.max.6`
- Falls back to `teams.max_members_default` if none granted

### Admin
| Permission | Description |
|-----------|------------|
| `sorekillteams.admin` | Admin command access |
| `sorekillteams.admin.reload` | Reload configs |
| `sorekillteams.admin.disband` | Force disband |
| `sorekillteams.admin.setowner` | Force owner change |
| `sorekillteams.admin.kick` | Force kick |
| `sorekillteams.admin.info` | View any team info |

---

## Configuration Overview

### Core Systems
- **Storage**
  - YAML backend
  - Autosave interval
  - Atomic writes (crash-safe)
  - Rolling backups
- **Teams**
  - Name validation rules
  - Reserved names + fuzzy matching
  - Permission-based size limits
- **Invites**
  - Expiry, cooldowns, caps
  - Multi-team handling rules
  - Re-invite refresh behavior
- **Chat**
  - Toggleable team chat
  - Custom formats
  - Admin spy with separate format
- **Friendly Fire**
  - Full damage-source control
  - Anti-spam messages
- **Homes**
  - Max homes per team
  - Warmups & cooldowns
  - Proxy-aware metadata
- **Admin Safety**
  - Enable/disable destructive admin actions
- **Integrations**
  - PlaceholderAPI
  - MiniPlaceholders

---

## Placeholders

When PlaceholderAPI or MiniPlaceholders is installed, team data can be used in:
- Chat formats
- Scoreboards
- Nametags
- Other compatible plugins

(Placeholder list will be expanded in future releases.)

---

## Planned Features

- Velocity & BungeeCord proxy support
- Cross-server teams & homes
- Team roles and permissions
- GUI-based team menus
- Scoreboard & nametag modules

All planned features prioritize **stability, performance, and network compatibility**.

---

## License

Licensed under the **Apache License, Version 2.0**  
See `LICENSE` for details.

---

## Contributing

Bug reports, feature requests, and pull requests are welcome.  
Please keep contributions focused and aligned with the plugin’s lightweight philosophy.

---

## Credits

- Developed by **Sorekill**
- Built for the **Chumbucket Network** — https://chumbucket.net

© 2025 Sorekill — Apache-2.0 License
