# SorekillTeams

**SorekillTeams** is a lightweight teams plugin for **Paper and Spigot** that provides clean team management, private team chat, team homes, and configurable friendly-fire protection.

Designed for modern servers and future proxy networks, SorekillTeams focuses on **core team mechanics** without bloated clan systems, claims, or RPG features.

---

## 🆕 Latest Release — 1.2.1

### Changelog

- 

> ⚠️ Cross-server features are **opt-in** and require SQL + Redis.  
> Single-server setups work out of the box.

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

- **Team creation & management**
  - Create, rename, transfer ownership, kick members, leave, or disband teams
- **Advanced invite system**
  - Expiring invites, cooldowns, invite caps, re-invite refresh support
- **Team chat**
  - `/tc` command or toggle mode
  - Admin spy support with separate formatting
- **Friendly-fire control**
  - Block or scale teammate damage
  - Supports melee, projectiles, potions, explosions, and tridents
  - Per-team toggle and admin bypass
- **Team homes**
  - Set and teleport to team homes
  - Warmups, cooldowns, and max-home limits
  - Network-aware metadata for proxy environments
- **GUI menu system**
  - Team management menus
  - Team browsing and member viewing
  - Invite management
  - Offline player heads with skin support
- **Update checker**
  - Spigot history-based update detection
  - Optional in-game OP notifications
- **Safe storage system**
  - YAML or SQL storage
  - Autosave, atomic writes, and rolling backups
- **Paper & Spigot compatible**
- **Optional placeholder support**
  - PlaceholderAPI and MiniPlaceholders
- **Lightweight and focused**
  - No economy, claims, or RPG systems

---

## Requirements

- **Java 21**
- **Paper or Spigot 1.21.x**
- *(Optional)* PlaceholderAPI 2.11.0+
- *(Optional)* MiniPlaceholders 3.0+

---

## Installation

### 🔹 Single-Server Setup (Most Servers)

This is the default and simplest setup.

1. Download `SorekillTeams-1.1.9.jar`
2. Place it in your server’s `plugins/` directory
3. Start or restart the server
4. Configure permissions (**LuckPerms recommended**)
5. Adjust `config.yml`, `messages.yml`, and `menus.yml` as needed

**Notes:**
- Uses YAML storage by default
- Redis and proxy features are **not required**
- Team homes work normally on this server only

---

### 🔹 Multi-Server / Proxy Setup (Advanced)

Use this if you run **Velocity or BungeeCord** with multiple backend servers.

**Requirements:**
- Shared SQL database (MySQL, MariaDB, PostgreSQL, etc.)
- Redis server for real-time sync
- Proxy (Velocity or BungeeCord)

**Steps:**
1. Set `storage.type` to a SQL backend
2. Enable Redis in `config.yml`
3. Give each backend server a **unique** `redis.server_id`
4. Set `homes.server_name` to match the **proxy server name**
   - ⚠️ This must exactly match the name used by the proxy
5. Enable `homes.proxy_mode` if using cross-server home teleports
6. Restart all backend servers

**What you get:**
- Network-wide team membership
- Cross-server invites and chat
- Team events synced instantly
- Network-aware team homes (teleport routing ready)

> SQL is the **source of truth**  
> Redis is the **real-time notification layer**

---

Reload configs anytime with:
'/sorekillteams reload'

---

## Commands

### Player Commands
| Command | Description | Permission |
|-------|-------------|------------|
| `/team` | Base team command | `sorekillteams.use` |
| `/team create <name>` | Create a team | `sorekillteams.create` |
| `/team invite <player>` | Invite a player | `sorekillteams.invite` |
| `/team invites` | View pending invites | `sorekillteams.invites` |
| `/team accept [team]` | Accept an invite | `sorekillteams.accept` |
| `/team deny [team]` | Deny an invite | `sorekillteams.deny` |
| `/team leave` | Leave your team | `sorekillteams.leave` |
| `/team info` | View team info | `sorekillteams.info` |
| `/team ff <on|off|toggle>` | Toggle friendly fire | `sorekillteams.ff` |

### Owner-Only Team Commands
| Command | Description | Permission |
|-------|-------------|------------|
| `/team kick <player>` | Kick a member | `sorekillteams.kick` |
| `/team transfer <player>` | Transfer ownership | `sorekillteams.transfer` |
| `/team rename <name>` | Rename your team | `sorekillteams.rename` |
| `/team disband` | Disband your team | `sorekillteams.disband` |
| `/team sethome` | Set a team home | `sorekillteams.sethome` |
| `/team delhome` | Delete a team home | `sorekillteams.delhome` |

### Team Homes
| Command | Description | Permission |
|-------|-------------|------------|
| `/team homes` | List team homes | `sorekillteams.homes` |
| `/team home` | Teleport to a team home | `sorekillteams.home` |

### Team Chat
| Command | Description | Permission |
|-------|-------------|------------|
| `/tc [message]` | Team chat or toggle mode | `sorekillteams.teamchat` |

### Admin Commands
| Command | Description | Permission |
|-------|-------------|------------|
| `/sorekillteams reload` | Reload configs and storage | `sorekillteams.admin.reload` |
| `/sorekillteams version` | Show plugin version | `sorekillteams.admin.version` |
| `/sorekillteams disband <team>` | Force disband a team | `sorekillteams.admin.disband` |
| `/sorekillteams setowner <team> <player>` | Force set team owner | `sorekillteams.admin.setowner` |
| `/sorekillteams kick <team> <player>` | Force kick a member | `sorekillteams.admin.kick` |
| `/sorekillteams info <team>` | View any team info | `sorekillteams.admin.info` |

---

## Permissions

### Wildcard
| Permission | Description | Default |
|-----------|-------------|---------|
| `sorekillteams.*` | Grants all permissions | op |

### Base
| Permission | Description | Default |
|-----------|-------------|---------|
| `sorekillteams.use` | Use `/team` | false |
| `sorekillteams.teamchat` | Use team chat | false |
| `sorekillteams.chat` | Legacy alias (maps to teamchat) | false |

### Team Management
| Permission | Description |
|-----------|-------------|
| `sorekillteams.create` | Create teams |
| `sorekillteams.invite` | Invite players |
| `sorekillteams.invites` | View pending invites |
| `sorekillteams.invitetoggle` | Toggle invites |
| `sorekillteams.accept` | Accept invites |
| `sorekillteams.deny` | Deny invites |
| `sorekillteams.leave` | Leave team |
| `sorekillteams.disband` | Disband team |
| `sorekillteams.info` | View team info |
| `sorekillteams.kick` | Kick members |
| `sorekillteams.transfer` | Transfer ownership |
| `sorekillteams.rename` | Rename team |
| `sorekillteams.ff` | Toggle friendly fire |

### Team Homes
| Permission | Description |
|-----------|-------------|
| `sorekillteams.homes` | List team homes |
| `sorekillteams.home` | Teleport to team home |
| `sorekillteams.sethome` | Set a team home |
| `sorekillteams.delhome` | Delete a team home |
| `sorekillteams.home.bypasscooldown` | Bypass home cooldown |

### Admin / Moderation
| Permission | Description |
|-----------|-------------|
| `sorekillteams.admin` | Admin command access |
| `sorekillteams.admin.reload` | Reload plugin |
| `sorekillteams.admin.version` | View version |
| `sorekillteams.admin.disband` | Force disband teams |
| `sorekillteams.admin.setowner` | Force set owner |
| `sorekillteams.admin.kick` | Force kick players |
| `sorekillteams.admin.info` | View any team info |
| `sorekillteams.spy` | Spy on team chat |
| `sorekillteams.friendlyfire.bypass` | Bypass FF protection |

### Team Size Overrides
| Permission | Description |
|-----------|-------------|
| `sorekillteams.max.<number>` | Override max team size (e.g. `sorekillteams.max.6`) |

---

## Planned Features

- Team roles and internal permissions

Suggestions are appreciated!

---

## License

Licensed under the **Apache License, Version 2.0**  
See `LICENSE` for details.

---

## Credits

- Developed by **Sorekill**
- Built for the **Chumbucket Network** — https://www.chumbucket.net

© 2025 Sorekill — Apache-2.0 License
