# Medieval Era — Minecraft server system

A server-side Medieval Era system for a Paper Minecraft server: kingdoms and territory, medieval
warfare and siege equipment, custom resources and equipment, defensive construction, a custom
medieval world, and full Bedrock support through Geyser — **without requiring any client mod**.

This directory holds the Minecraft project. It lives next to the rest of the repository and does
not modify or depend on anything outside `minecraft/`.

## Requirements

| Requirement | Version | Notes |
| --- | --- | --- |
| JDK | **25** | Minimum for Paper `26.2`; provided automatically by the Foojay toolchain resolver or by CI |
| Gradle | none needed | Use the checked-in wrapper (`./gradlew`) |
| Paper server | **26.2** | The single server target |

## Build

```bash
cd minecraft
./gradlew build          # compiles, runs unit tests and packages the plugin
./gradlew test           # unit tests only
```

Output artifact:

```
minecraft/medieval-paper/build/libs/Medieval-0.1.0-SNAPSHOT.jar
```

The jar is self-contained (the `medieval-api` and `medieval-core` classes are merged in);
`paper-api` is `compileOnly` and is provided by the server at runtime.

CI (`.github/workflows/minecraft.yml`) builds and tests the same artifact on every change and
uploads it as a build artifact.

## Module layout

```text
minecraft/
├── medieval-api/     Pure domain API and value types. No Bukkit, no Paper.
├── medieval-core/    Version-independent logic + unit tests. Never imports Bukkit.
└── medieval-paper/   Platform adapter: bootstrap, config, commands, listeners, GUIs.
```

`medieval-geyser/` (Bedrock bridge) and `medieval-resourcepack/` (asset pipeline) are added when
those phases land — they are deliberately absent rather than present-but-empty.

The rule that keeps the system portable: gameplay identity lives in the core, and the client only
ever sees a representation of it.

```text
custom item id (server)
        │
        ▼
server gameplay state (core)
        │
        ├──▶ Java representation (resource pack / components)
        └──▶ Bedrock representation (Geyser mappings / Bedrock pack)
```

## Verified client compatibility stack

Every version below was verified against the projects' own documentation and download APIs, not
assumed.

| Piece | Version / fact | Source of truth |
| --- | --- | --- |
| Paper | `26.2` supported, requires Java 25 (artifact `io.papermc.paper:paper-api:26.2.build.129-stable`) | Paper download API + docs |
| Paper `1.21.11` | End of life upstream since 2026-06-15 — **we do not run a server on it** | Paper download API |
| Geyser | Emulates a **Java 26.2** client; supports **Bedrock 26.30–26.51** | GeyserMC supported-versions docs |
| Geyser API | Usable from a Paper plugin (`GeyserApi.api()`, event bus, custom items/blocks, packs, forms) | GeyserMC API docs |
| Floodgate | `FloodgateApi.getInstance().isFloodgatePlayer(uuid)` for Bedrock detection | GeyserMC Floodgate docs |
| ViaVersion | `5.12.0+` — added **26.3 client** support | ViaVersion release notes |
| ViaBackwards | Runs on servers 1.10+, older clients → newer server; contains the `26_1 → 1.21.11` protocol step | ViaBackwards README + repository |
| Terralith | Datapack supports `26.2`; **cannot be removed from an existing world** | Modrinth page |

Resulting runtime layout:

```text
Java 26.3 client ─┐
Java 26.2 client ─┼─▶ Paper 26.2  + ViaVersion (26.3 → 26.2) + ViaBackwards (1.21.11 → 26.2)
Java 1.21.11     ─┘                    ▲
Bedrock 26.30–26.51 ──▶ Geyser ────────┘   (native 26.2 emulation, no translation hop)
```

One plugin artifact serves all of them. Players install nothing.

## Server deployment

1. Drop the built jar into `plugins/`.
2. Install the server-side support plugins (these are never bundled):
   - `Geyser-Spigot` + `floodgate` for Bedrock players,
   - `ViaVersion` + `ViaBackwards` for the 1.21.11 and 26.3 Java clients.
3. Add the Terralith datapack **before** the world is generated for the first time.
4. Start with a Java 25 runtime.

`test-server/` contains instructions and a helper script for a local dev server.

## Configuration

| File | Purpose |
| --- | --- |
| `config.yml` | Gameplay values: deathban, dimensions, siege machines, territory limits |
| `messages.yml` | MiniMessage templates; placeholders use `{braces}` |

Both are validated on load. An invalid value is reported in the console with its path and falls
back to the documented default instead of crashing startup. `/medieval reload` re-reads both.

## Command surface

| Command | Permission | Status |
| --- | --- | --- |
| `/medieval help` | `medieval.command.use` (default: everyone) | implemented |
| `/medieval info` | `medieval.command.use` | implemented |
| `/medieval reload` | `medieval.command.reload` (default: op) | implemented |

Commands are registered through Paper's Brigadier API at runtime rather than declared in
`plugin.yml`, so listing and tab completion follow the command's `requires` predicate.

## Implementation status

Honest status — nothing below is represented by an interface without an implementation.

**Implemented and unit-tested now**

- Gradle multi-module build, Gradle wrapper pinned to 9.7.1, CI pipeline, reproducible archives.
- Platform abstraction (`MedievalPlatform`, `BedrockDetector`) with the Paper implementation.
- Validated, immutable settings model plus YAML and in-memory settings sources, with reload.
- MiniMessage message service with `{placeholder}` substitution and reload.
- Event bus with failure isolation, service registry with ordered lifecycle.
- Core value types: `CustomItemId`, `KingdomRank`, `ChunkPosition`.
- Deathban domain service (absolute expiry, admin override, purge) — **not yet wired to player
  events and not yet backed by SQL**, so it does not yet persist. See below.
- Command surface above, capability detection and startup logging.

**Not implemented yet — no placeholder code exists for these**

| Phase | Content |
| --- | --- |
| Persistence | SQLite storage layer (`players`, `kingdoms`, `claims`, `sieges`, `structures`, `deathbans`, `world_state`) — replaces the in-memory deathban store with a durable one and wires the deathban into join/respawn |
| Items | Custom item registry, medieval weapons and armour, attributes, Geyser/Bedrock mappings |
| Recipes | Recipe registry and crafting stations (Bedrock-safe: no custom anvil/smithing recipes) |
| Kingdoms | Kingdoms, membership, ranks, invitations, wars |
| Territory | Claims, protection, siege exceptions, admin overrides |
| Defenses | Barricades, walls, gates, towers, traps, structure HP |
| Siege | Battering ram, catapult, ballista, siege tower, projectiles, ownership |
| Dimensions | Nether/End gating with persisted state and admin/event toggles |
| GUIs | Inventory-first menu framework, usable from Java and Bedrock |
| Admin | `/medieval give|kingdom|siege|event|world`, private owner catalogue GUI |
| World | Terralith integration, border + pre-generation, ores, structures |
| Assets | Generated Java pack, Bedrock pack and Geyser mappings from one definition set |

## Known constraints (verified, not guesses)

- Bedrock renders **no item/block display entities** — Bedrock visuals use custom Bedrock items,
  custom Bedrock blocks (Geyser mappings) or plain entities; display entities are Java-only
  decoration.
- Geyser **cannot distinguish left/right clicks in inventories**, so GUIs use distinct slots,
  items and shift-click only.
- Bedrock does not support **custom enchantments**, custom anvil/smithing recipes or custom
  furnace cook times — progression uses attributes, components and server-side logic instead.
- Test-instance blocks do not exist on Bedrock; the administrative catalogue substitutes them.
- Item components used by custom items are restricted to the 1.21.11-era set so ViaBackwards can
  translate them for the oldest supported client.
