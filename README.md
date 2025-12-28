# SorekillTeams

**SorekillTeams** is a lightweight teams plugin for **Paper and Spigot** that provides clean team management, private team chat, team homes, and configurable friendly-fire protection.

Designed for modern servers and future proxy networks, SorekillTeams focuses on **core team mechanics** without bloated clan systems, claims, or RPG features.

---

## 🆕 Latest Release — 1.1.9

### Changelog
- 

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
  - Network-aware metadata for future proxy support
- **Update checker**
  - Spigot history-based update detection
  - Optional in-game OP notifications
- **Safe storage system**
  - Autosave, atomic writes, and rolling backups
- **Paper & Spigot compatible**
- **Optional placeholder support**
  - PlaceholderAPI and MiniPlaceholders
- **Lightweight and focused**
  - No economy, claims, or RPG systems

---

## Menus (New)

SorekillTeams includes a **built-in GUI menu system** for intuitive team interaction.

- Inventory-based menus for team management
- Team browsing and member viewing
- Invite management and confirmations
- Offline player heads with proper skin rendering
- Permission- and context-aware actions
- Fully configurable via `menus.yml`

The menu system is designed to remain **lightweight and optional**, complementing command-based workflows rather than replacing them.

---

## Requirements

- **Java 21**
- **Paper or Spigot 1.21.x**
- *(Optional)* PlaceholderAPI 2.11.0+
- *(Optional)* MiniPlaceholders 3.0+

---

## Installation

1. Download `SorekillTeams.jar`
2. Place it in your server’s `plugins/` directory
3. Start or restart the server
4. Configure permissions (**LuckPerms recommended**)
5. Adjust `config.yml` and `messages.yml` as needed

Reload configs anytime with:
'/sorekillteams reload'

---

## Commands

| Command | Usage | Description | Permission |
|--------|-------|-------------|------------|
| `/team` | `/team` | Base team command | `sorekillteams.use` |
| `/team create` | `/team create <name>` | Create a team | `sorekillteams.create` |
| `/team invite` | `/team invite <player>` | Invite a player | `sorekillteams.invite` |
| `/team invites` | `/team invites` | View pending invites | `sorekillteams.invites` |
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
| `/sorekillteams reload` | `/sorekillteams reload` | Reload configs/messages | `sorekillteams.admin.reload` |
| `/sorekillteams version` | `/sorekillteams version` | Show plugin version | `sorekillteams.admin.version` |

---

## Permissions

### Wildcard (recommended for admins)

| Permission | Description | Default |
|------------|-------------|---------|
| `sorekillteams.*` | Grants all SorekillTeams permissions | op |

---

### Base

| Permission | Description | Default |
|------------|-------------|---------|
| `sorekillteams.use` | Use `/team` | false |
| `sorekillteams.teamchat` | Use team chat (`/tc`, toggle) | false |
| `sorekillteams.chat` | Legacy alias for team chat | false |

---

### Team Management

| Permission | Description |
|------------|-------------|
| `sorekillteams.create` | Create teams |
| `sorekillteams.invite` | Invite players |
| `sorekillteams.invites` | View pending invites |
| `sorekillteams.accept` | Accept invites |
| `sorekillteams.deny` | Deny invites |
| `sorekillteams.leave` | Leave team |
| `sorekillteams.disband` | Disband team |
| `sorekillteams.kick` | Kick members |
| `sorekillteams.transfer` | Transfer ownership |
| `sorekillteams.rename` | Rename team |
| `sorekillteams.ff` | Toggle friendly fire |

---

### Team Homes

| Permission | Description |
|------------|-------------|
| `sorekillteams.homes` | View or list team homes |
| `sorekillteams.home` | Teleport to a team home |
| `sorekillteams.sethome` | Set a team home |
| `sorekillteams.delhome` | Delete a team home |
| `sorekillteams.home.bypasscooldown` | Bypass home cooldowns |

---

### Admin

| Permission | Description | Default |
|------------|-------------|---------|
| `sorekillteams.admin` | Admin command access | op |
| `sorekillteams.admin.reload` | Reload configs/messages | op |
| `sorekillteams.admin.version` | View plugin version | op |
| `sorekillteams.admin.disband` | Force disband teams | op |
| `sorekillteams.admin.setowner` | Force owner change | op |
| `sorekillteams.admin.kick` | Force kick players | op |
| `sorekillteams.admin.info` | View any team info | op |
| `sorekillteams.spy` | Spy on team chat | op |
| `sorekillteams.friendlyfire.bypass` | Bypass FF protection | op |

---

## Planned Features

- Velocity and BungeeCord proxy support
- Cross-server teams and homes
- Team roles and permissions
- Scoreboard and nametag modules

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
