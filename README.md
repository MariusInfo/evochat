# ðŸ’¬ EvoChat

Professional chat management and moderation suite for **Minecraft Forge 1.20.1**.

## Features
- **Online-Time Mutes:** Mute durations countdown ONLY while the player is actively connected to the server.
- **SQL Persistence:** Mute timers persist in the database across player disconnects and server restarts.
- **Global Chat Control:** Toggle server-wide chat on/off during events or raids (/mutechat).
- **Staff Bypass:** Configurable LuckPerms bypass permissions.

## Commands
| Command | Permission | Description |
| :--- | :--- | :--- |
| /mutechat | evochat.mutechat | Toggles global chat on or off |
| /mute <player> <minutes> [reason] | evochat.mute | Mutes a player for online minutes |
| /unmute <player> | evochat.mute | Unmutes a player |

## Requirements
- Minecraft Forge **1.20.1** (47.4.18+)
- Java **17**
- **EvoCore** 1.0.0+

## License
All Rights Reserved - EvoCraft Network
