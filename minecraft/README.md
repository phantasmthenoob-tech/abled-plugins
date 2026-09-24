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
│   ├── MedievalPlatform, BedrockDetector, MedievalScheduler   host abstraction
│   └── MedievalService, EventBus, kingdom/item value types
├── medieval-core/    Version-independent logic + unit tests. Never imports Bukkit.
│   ├── deathban/     DeathbanService, stores, login access rules
│   ├── storage/      Database, StorageService, migrations, JDBC access layer
│   ├── player/       PlayerIdentityService + repository
│   ├── kingdom/      KingdomRepository, membership, DuplicateKingdomException
│   ├── territory/    ChunkPosition, ClaimRecord, ClaimRepository
│   ├── world/        persisted runtime world state
│   └── config/ message/ util/ service/ event/
└── medieval-paper/   Platform adapter. The only module that imports Bukkit.
    ├── compat/       version/platform compatibility layer (PaperScheduler today)
    ├── storage/      SQLite bootstrap: URL, driver, pragmas, repositories
    ├── listener/     LoginGateListener, DeathbanListener
    ├── command/      /medieval command tree
    └── config/ message/ capability/
```

The split is deliberate: game rules live in `medieval-core` and are unit-tested without a server,
while everything that differs between server versions or platforms (scheduler, events, command
registration, SQLite wiring) lives behind `medieval-api` interfaces and their `medieval-paper`
implementations. Adding a platform means implementing those interfaces, not editing the rules.

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
| Paper `libraries` | `plugin.yml` `libraries:` entries are downloaded from Maven Central and added to the plugin classpath | Paper `plugin.yml` docs |
| SQLite JDBC | `org.xerial:sqlite-jdbc:3.53.4.0` (latest release); never shaded, supplied by the server | Maven Central metadata |
| Brigadier | `com.mojang:brigadier:1.3.10` is a transitive dependency of `paper-api` and is served by the PaperMC repository, not Maven Central | paper-api POM + repository probe |

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
back to the documented default instead of crashing startup. `/medieval reload` re-reads both, and
the deathban duration is read through the settings snapshot, so a reload takes effect immediately.

`dimensions.nether.enabled` and `dimensions.end.enabled` are **defaults, not the live state**: the
first `/nether open` or `/end close` records the decision in the database, and from then on the
stored value wins so an event that opened a dimension is not undone by the next restart. A gate
nobody has touched keeps following `config.yml`, so editing the file and running `/medieval reload`
still works until it is overridden.

## Persistence

One SQLite file, `plugins/Medieval/medieval.db`, owns all durable state. The JDBC driver is **not**
bundled: `plugin.yml` declares it under `libraries`, and the server downloads it from Maven Central
before the plugin is loaded.

| Concern | How it is handled |
| --- | --- |
| Driver | `org.xerial:sqlite-jdbc` via `plugin.yml` → `libraries`; the repository never commits a jar |
| Schema | Versioned SQL migrations under `medieval-core/src/main/resources/db/migration/`, applied in order, each inside one transaction with its version row |
| Fresh install | Migration `001` creates `players`, `kingdoms`, `kingdom_members`, `claims`, `deathbans`, `world_state` |
| Upgrades | Only pending migrations run; a database that records an unknown (newer) version aborts startup instead of running against an unexpected schema |
| Identity | The UUID is the key everywhere; names are display/lookup attributes only, never ownership |
| Statements | Prepared statements only, issued from small repositories; no ORM, no SQL outside `medieval-core/src/main/java/.../storage` + the repositories |
| Transactions | Multi-row writes (kingdom + founder membership, claim takeover) are atomic; a failure rolls back |
| Durability | `journal_mode = WAL`, `synchronous = NORMAL`, `foreign_keys = ON`, `busy_timeout = 5000` on every connection |
| Initialisation failure | The plugin logs the cause and disables itself rather than running a "persistent" game without persistence |
| Shutdown | Background tasks are cancelled first, then services, then the connection is closed; a statement already running finishes, so a shutdown during a write commits or rolls back |
| Bad data | Unreadable rows raise `MalformedRowException` and are skipped with a logged error instead of silently becoming null/zero |

The `wars`, `sieges` and `structures` tables are deliberately absent: they arrive with the
migrations of the phases that implement them, so the schema never advertises data no code keeps.

### Thread ownership

| Work | Thread | Why |
| --- | --- | --- |
| `StorageService.open()` / `close()` | startup / shutdown thread | Schema migration and connection teardown are startup concerns |
| Deathban check + profile write on login | asynchronous pre-login thread | Keeps a login off the tick thread; the decision is taken before the player joins |
| Deathban write on death | tick thread | One single-row statement; it must be committed before the player respawns, and the storage layer serialises access |
| `/medieval deathban ...` | database work asynchronous, messages handed back with `runSync` | Admin commands never block the tick thread on disk, and never touch Bukkit state off it |
| Dimension gate read (portal travel) | tick thread | Served from an immutable in-memory snapshot, so a portal never waits on disk |
| Dimension gate change (`/nether`, `/end`) | database write asynchronous, reply + evacuation on `runSync` | Same contract as the admin commands above |
| Expired-ban purge | asynchronous repeating task | Housekeeping, cancelled before storage closes |
| Repository reads/writes | any thread | `Database` guards its connection, so point reads and single-row writes are safe from any thread |

## Command surface

| Command | Permission | Status |
| --- | --- | --- |
| `/medieval help` | `medieval.command.use` (default: everyone) | implemented |
| `/medieval info` | `medieval.command.use` | implemented |
| `/medieval reload` | `medieval.command.reload` (default: op) | implemented |
| `/medieval deathban check <player>` | `medieval.command.deathban` (default: op) | implemented |
| `/medieval deathban set <player> <duration>` | `medieval.command.deathban` | implemented |
| `/medieval deathban clear <player>` | `medieval.command.deathban` | implemented |
| `/medieval deathban list` | `medieval.command.deathban` | implemented |
| `/nether open|close|status` | `medieval.command.dimension` (default: op) | implemented |
| `/end open|close|status` | `medieval.command.dimension` (default: op) | implemented |

Commands are registered through Paper's Brigadier API at runtime rather than declared in
`plugin.yml`, so listing and tab completion follow each branch's `requires` predicate: a sender
without `medieval.command.deathban` never sees the administrative branches, and the permission
defaults to op so they are not exposed to ordinary players.

The dimension gates are enforced, not merely recorded: while a gate is closed, portal travel into
that dimension is cancelled for players and for every other entity (a mounted player, a mob, a
chest pushed through a portal), and anyone already inside is moved back to the overworld. Building a
portal is still allowed, so a dimension an event opens later already has its portals lit.
Closing a gate is an asynchronous database write; the reply and the evacuation are handed back to
the tick thread, so no Bukkit state is touched off it.

## Implementation status

Honest status — nothing below is represented by an interface without an implementation.

**Implemented and unit-tested now**

- Gradle multi-module build, Gradle wrapper pinned to 9.7.1, CI pipeline, reproducible archives.
- Platform abstraction (`MedievalPlatform`, `BedrockDetector`) with the Paper implementation.
- Validated, immutable settings model plus YAML and in-memory settings sources, with reload.
- MiniMessage message service with `{placeholder}` substitution and reload.
- Event bus with failure isolation, service registry with ordered lifecycle.
- Core value types: `CustomItemId`, `KingdomRank`, `ChunkPosition`.
- **Persistence**: SQLite `Database`/`StorageService`, versioned migrations, and repositories for
  players, kingdoms + membership, claims, deathbans and world state — all exercised by tests against
  real temporary SQLite files (including reopen, rollback and malformed-row cases).
- **Deathban, wired end to end**: a death writes a durable ban and ends the session; the
  asynchronous pre-login gate reads the ban and refuses the login with the remaining time; expired
  rows are released on sight and swept in the background; `/medieval deathban check|set|clear|list`
  provides the administrative interface behind `medieval.command.deathban`.
- **Dimension gating, wired end to end**: `/nether` and `/end` each take `open`, `close` and
  `status`; the decision is persisted in `world_state` and therefore survives a restart, a gate with
  no stored row follows `config.yml`, and the decision is published on the event bus as
  `DimensionAccessChanged` so events, GUIs and announcements react instead of being wired in.
- Startup/shutdown lifecycle: storage opens before services, and the plugin disables itself if the
  database cannot be opened instead of running without persistence.
- Command surface above, capability detection and startup logging.

`KingdomRepository` and `ClaimRepository` are implemented and unit-tested but nothing calls them
yet: kingdoms and territory are their own phases and will use these repositories rather than new
persistence code.

**Not implemented yet — no placeholder code exists for these**

| Phase | Content |
| --- | --- |
| Items | Custom item registry, medieval weapons and armour, attributes, Geyser/Bedrock mappings |
| Recipes | Recipe registry and crafting stations (Bedrock-safe: no custom anvil/smithing recipes) |
| Kingdoms | Kingdoms, membership, ranks, invitations, wars |
| Territory | Claims, protection, siege exceptions, admin overrides |
| Defenses | Barricades, walls, gates, towers, traps, structure HP |
| Siege | Battering ram, catapult, ballista, siege tower, projectiles, ownership |
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
