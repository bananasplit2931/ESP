# ESP - Even Simpler PIN
A lightweight 4-digit PIN authentication plugin for [Paper](https://papermc.io) 1.21+.
Players authenticate using the `/pin` command in chat.
---
## Features
- **Chat-based authentication** - players enter their PIN via `/pin <4 digits>`
- **First-time setup** - new players set and confirm their PIN on first join
- **Fully locked until authenticated** - frozen, blinded, and protected from damage
- **Attempt limit** - players are kicked after 3 wrong attempts
- **Timeout** - players are kicked if they take too long to enter their PIN
- **SHA-256 hashed PINs** - PINs are never stored in plain text
- **Admin reset command** - reset any player's PIN from the console or in-game
---
## Installation
1. Download the latest `.jar` from [Releases](../../releases) or [Modrinth](https://modrinth.com/plugin/even-simpler-pin).
2. Place the `.jar` in your server's `plugins/` folder.
3. Restart your server.
**Requirements:** Paper 1.21+ - Java 25+
---
## Usage
ESP requires all players to authenticate with a 4-digit PIN before interacting with the server. The PIN is set on first join and must be entered on every subsequent join.
### Players
On joining, a prompt appears in chat asking you to authenticate.
| Action | How |
|---|---|
| Enter your PIN | `/pin <4 digits>` |
- **First join:** run `/pin <4 digits>` to choose your PIN, then run the same command again to confirm it.
- **Subsequent joins:** run `/pin <your PIN>` to log in.

All commands except `/pin` are blocked until you are authenticated.
### Administrators
| Command | Description |
|---|---|
| `/espadmin reset <player>` | Clears a player's PIN, forcing them to set a new one |
Requires operator status (`op`).
---
## Configuration
PINs are stored in `plugins/ESP/pins.yml` as SHA-256 hashes.
The following constants can be changed by editing the source before compiling:
| Constant | Default | Description |
|---|---|---|
| `MAX_ATTEMPTS` | `3` | Wrong attempts before kick |
| `TIMEOUT_SECS` | `30` | Seconds to enter PIN before kick |
---
## Building from source
Requires Java 25 and Maven.
```bash
git clone https://github.com/bananasquare/ESP.git
cd ESP
mvn clean package
```
The compiled JAR will be in `target/ESP-1.0.0.jar`.
Alternatively, every push to `main` triggers a [GitHub Actions](.github/workflows/build.yml) build - download the artifact from the **Actions** tab.
---
## License
[Apache License 2.0](LICENSE) - bananasquare
