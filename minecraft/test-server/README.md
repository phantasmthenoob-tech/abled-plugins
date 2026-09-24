# Local test server

A throwaway Paper server for manually verifying the plugin against real clients.

**Server binaries, worlds and logs are never committed.** `minecraft/.gitignore` already excludes
them; only this README and the helper script are tracked.

## Setup

```bash
cd minecraft
sh test-server/fetch-dependencies.sh
```

The script downloads, into this directory:

| File | Source |
| --- | --- |
| `server.jar` | Paper `26.2`, latest stable build resolved through the official fill API |
| `plugins/Geyser-Spigot.jar` | `download.geysermc.org` (official download API) |
| `plugins/floodgate.jar` | `download.geysermc.org` (official download API) |
| `eula.txt` | written locally (`eula=true` — running a server requires accepting Mojang's EULA) |

Two dependencies are deliberately **not** downloaded automatically because they are distributed
through Hangar rather than a stable download API. Fetch them by hand and drop them in `plugins/`:

- **ViaVersion** — <https://hangar.papermc.io/ViaVersion/ViaVersion>
- **ViaBackwards** — <https://hangar.papermc.io/ViaVersion/ViaBackwards>

Both are required for cross-client support: ViaVersion lets a newer client (26.3) join the 26.2
server, ViaBackwards lets an older client (1.21.11) join the same server.

## Build and install the plugin

```bash
cd minecraft
./gradlew build
cp medieval-paper/build/libs/Medieval-*.jar test-server/plugins/
```

## Run

A Java **25** runtime is required.

```bash
cd minecraft/test-server
java -Xmx4G -jar server.jar --nogui
```

Bedrock clients connect to port `19132` (UDP) once Geyser has started; Java clients on `25565`.

## Manual verification checklist

Automated tests cover core logic only. The following must be checked against real clients, since
protocol translation and Bedrock inventory behaviour cannot be unit-tested:

- [ ] Java 26.2 client: login, `/medieval info`, `/medieval reload`
- [ ] Java 26.3 client via ViaVersion: same checks
- [ ] Java 1.21.11 client via ViaBackwards: same checks, plus custom items render and function
- [ ] Bedrock via Geyser: login, chat, `/medieval info`
- [ ] Bedrock inventory interaction: GUIs open, shift-click works, no left/right click dependency
- [ ] Bedrock disconnect/reconnect and world switching
- [ ] Resource pack refused: gameplay still works with fallback vanilla appearances
- [ ] Restart persistence for every subsystem that stores data
