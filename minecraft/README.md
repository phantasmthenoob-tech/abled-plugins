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
│   ├── combat/       MeleeWeapon, Consumable, CombatRules - the combat enchantment decisions
│   ├── deathban/     DeathbanService, stores, login access rules
│   ├── storage/      Database, StorageService, migrations, JDBC access layer
│   ├── player/       PlayerIdentityService + repository
│   ├── kingdom/      KingdomRepository, membership, DuplicateKingdomException
│   ├── territory/    ChunkPosition, ClaimRecord, ClaimRepository
│   ├── world/        persisted runtime world state
│   └── config/ message/ util/ service/ event/
└── medieval-paper/   Platform adapter. The only module that imports Bukkit.
    ├── compat/       version/platform compatibility layer (PaperScheduler today)
    ├── combat/       combat enchantment rules: quick charge speed, shield piercing, infinity
    ├── catalogue/    owner item catalogue: registry index, paged chest menu, chat search
    ├── storage/      SQLite bootstrap: URL, driver, pragmas, repositories
    ├── listener/     login gate, deathban, dimension gate, catalogue chat capture
    ├── command/      /medieval command tree, /nether, /end
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
| `config.yml` | Gameplay values: deathban, dimensions, siege machines, territory limits, combat enchantment rules, hidden owner tools |
| `messages.yml` | MiniMessage templates; placeholders use `{braces}` |

Both are validated on load. An invalid value is reported in the console with its path and falls
back to the documented default instead of crashing startup. `/medieval reload` re-reads both, and
the deathban rules are read through the live policy rather than cached, so a reload takes effect
immediately.

**Both files are layered over the copies bundled in the jar.** Bukkit writes a bundled resource only
when the file is absent and never merges new keys into a file that already exists, so on a server
that is upgrading, a key this version introduced would otherwise be unknown - every new message
would render as `Missing message: <key>`, which is what a menu full of nothing actually means. With
the bundled copy layered underneath:

| | |
| --- | --- |
| A key added by an update | works immediately, no hand-editing |
| A key you have edited | your wording wins, including one you deliberately blanked |
| The file on disk | is still the only thing read and written - comments and ordering survive |
| A file that is behind | is reported once at startup, naming the keys filled from the bundle |

`dimensions.nether.enabled` and `dimensions.end.enabled` are **defaults, not the live state**: the
first `/nether open` or `/end close` records the decision in the database, and from then on the
stored value wins so an event that opened a dimension is not undone by the next restart. A gate
nobody has touched keeps following `config.yml`, so editing the file and running `/medieval reload`
still works until it is overridden.

`deathban.enabled` and `deathban.duration-seconds` are **defaults too**, with the same rule as the
gates: `/medieval deathban on|off|toggle` and `/medieval deathban duration <time>` record the
decision in the database, so a switch flipped for an event is not undone by the next restart, and
`/medieval deathban reset` hands both values back to the file. The two are tracked separately -
setting a duration does not freeze the toggle. `/medieval deathban status` prints what is in force
and which of the two it came from, and `/medieval info` shows the same thing.

A duration change only affects bans written from then on: an existing ban stores an absolute expiry,
so shortening the rule never silently releases someone who is already serving it. Use
`/medieval deathban clear <player>` for that.

`admin.secret-owner` names the one player allowed to use the hidden `/medieval statuscheck`
catalogue. It accepts a player name (case-insensitive) or a UUID, and it is **not** a permission -
the command is invisible to every other player including other operators. Use a UUID on an
offline-mode server: names are not protected there, so anyone who registers the name first inherits
the command. `/medieval reload` applies a change immediately.

An `admin:` block does not have to be present for this to work - a `config.yml` from an older
version is topped up from the bundled copy at load, so the key resolves to the shipped default
(`Disgraced_`) either way. Add the block to your own file only if you want the setting documented
where you edit it.

## Owner catalogue

`/medieval statuscheck` opens a chest-style catalogue of every item the server knows about,
including the ones a player cannot normally obtain - barriers, command blocks, structure blocks, the
debug stick, spawners, bedrock and the rest. Clicking gives you the item; nothing is crafted, farmed
or looked up.

```text
row 1  (slots  0- 8)  category tabs: Everything, Building Blocks, Redstone, Tools & Utilities,
                      Combat, Food & Drinks, Ingredients, Spawn Eggs, Admin & Technical
rows 2-5 (slots 9-44) up to 36 items of the current page
row 6  (slots 45-53) previous | search | take amount | custom amount | enchant | gamemode /
                      clear search / run command | page | next | close
```

The sixth slot of the bottom row is shared three ways, by state: while browsing it is the
**gamemode switcher** — click it, type `1`, `2` or `3` (or `survival`, `creative`, `spectator`) in
chat, and your gamemode changes, with the catalogue reopening afterwards (spectator cannot reopen
an inventory, so it only confirms the switch). While the **Admin tab** is open it is the
**run-command** book-and-quill: click it, type a command in chat (the leading `/` is optional), and
it is dispatched as the console, with the result echoed back and the catalogue reopened. While a
search is open the same slot is the **clear search** button, and leaving the search brings the
previous button back. The share is forced by Bedrock: Geyser translates chest inventories up to 54
slots, so the menu must not grow a seventh row.

| Action | Result |
| --- | --- |
| Click an item | Takes the amount selected by the **Take amount** button (1, 8, 16, 32, 64) — or the chat-typed amount while one is set |
| Right-click or shift-click an item | Takes a full stack |
| Take amount button | Cycles 1 → 8 → 16 → 32 → 64 |
| Custom amount button | Arms chat-amount mode: click an item, type the exact amount, it is handed over at once. While a typed amount is in force the button shows it; clicking it drops the override |
| Enchant button | Arms enchant mode: click an item to open its enchantment picker |
| Click a tab | Switches category, back to page 1; disarms both modes |
| Click the search button | Closes the menu and asks in chat what to search for |
| Type a query | Shows every matching item, from every tab |
| Click clear search | Leaves the search and browses the tabs again |
| Click gamemode (while browsing) | Closes the menu and asks for `1` = survival, `2` = creative, `3` = spectator in chat — the mode's name or `s`/`c`/`sp` also works |
| Click run command (Admin tab) | Closes the menu and asks for a command in chat, run as the console — the leading `/` is optional, the result is echoed, the menu comes back |
| Close button | Closes the menu |

Both page buttons are drawn on every page; the one that cannot be used is dimmed and inert rather
than blank, so the row never changes shape. That is also where "Missing message" used to appear:
a key the file did not define still renders as a button, just a red one.

Every element carries hover text explaining what it does - the buttons, the tabs, and each item
("click to take 1, right-click or shift-click for a full stack"). That text lives in `messages.yml`,
and a multi-line value there becomes multi-line lore, so wording is editable without a rebuild and
nothing is hardcoded in bytecode. The hint on a catalogue entry is presentation only: what you take
is a clean stack built from the item type, never the menu's copy of it.

Details that matter in practice:

- **The listing is built from the server's own registry**, not a hardcoded item list, so it is correct
  for whatever version the server runs and every obtainable item is reachable.
- **Bedrock/Geyser works.** Nothing depends on telling a left-click from a right-click, because
  Geyser cannot: every Bedrock tap arrives as a left-click. A tap therefore takes the selected
  amount, and the Take amount button is how a touch player takes stacks - shift-click is not
  reachable on touch. See the Geyser constraint below.
- **Search is typed into chat.** The sign editor and anvil renaming a Java player could use are
  exactly the screens Geyser does not carry across, so the search button closes the menu and asks in
  chat, and the next line is captured before it broadcasts (`cancel` abandons it). Only a player with
  a prompt outstanding is affected - ordinary chat is never inspected - and an unanswered prompt
  expires after 45 seconds rather than eating a line sent minutes later. A query always runs over
  **Everything** rather than the open tab, because a search that came back empty only for having the
  wrong tab open would look broken.
- **Amounts can be typed too.** The presets cap out at 64, and a slot cannot hold more anyway, but
  "57 iron ingots" is a real ask: the custom-amount button arms a mode whose next item click asks
  for the amount in chat and hands it over immediately. The answer accepts only a plain number and
  is clamped to the item's stack size, so it can always be delivered as one stack; while a typed
  amount is in force it overrides the cycled preset for plain clicks (right-/shift-click still means
  a full stack), and the button shows the number and clears it on the next click.
- **The enchantment picker.** Arm the enchant button, click any item, and a second GUI opens: every
  enchantment in the server's registry as its own button, with a live preview of the item being
  built. Clicking an enchantment asks for its level in chat - a number, a roman numeral (`II`) or a
  word (`two`), clamped to the enchantment's real maximum. Repeat for as many enchantments as you
  want, Clear empties the draft, and Done hands over the finished item (dropping any overflow, like
  every catalogue take). Back returns to the item listing without giving anything. The item given is
  a clean stack with the chosen enchantments, exactly as an owner `/give` would have produced.
- **Nothing moves by vanilla rules.** Every click and drag in the menu is cancelled and then
  interpreted, so a catalogue button can never be picked up onto the cursor, hotbar-swapped, or
  dropped with Q; items arrive only through the take path, which decides the amount itself.
- **A full inventory drops the remainder** at the player's feet and says so, instead of losing the
  items silently.
- **Adding items is a registry walk**, so a new version's items appear with no code change. An item
  the server reports no category for is still listed under Everything rather than being dropped.

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
| `/medieval deathban status` | tick thread | Answered from the live policy's fields, so it needs no storage round trip |
| Catalogue search answer | captured on the asynchronous chat thread, menu reopened on `runSync` | A chat event never carries inventory work |
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
| `/medieval deathban on|off|toggle` | `medieval.command.deathban` | implemented |
| `/medieval deathban duration <duration>` | `medieval.command.deathban` | implemented |
| `/medieval deathban status` | `medieval.command.deathban` | implemented |
| `/medieval deathban reset` | `medieval.command.deathban` | implemented |
| `/nether open|close|status` | `medieval.command.dimension` (default: op) | implemented |
| `/end open|close|status` | `medieval.command.dimension` (default: op) | implemented |
| `/medieval statuscheck` | **owner only** - `admin.secret-owner`, no permission node | implemented |
| `/medieval statuscheck` enchantment picker | **owner only** - in-menu GUI, no command | implemented |
| `/medieval cmdblock <mode> <item> <command>` | **owner only** - `admin.secret-owner`, no permission node | implemented |
| `/medieval cmdblock mode\|trigger` (switch a held wand) | **owner only** - `admin.secret-owner`, no permission node | implemented |

Commands are registered through Paper's Brigadier API at runtime rather than declared in
`plugin.yml`, so listing and tab completion follow each branch's `requires` predicate: a sender
without `medieval.command.deathban` never sees the administrative branches, and the permission
defaults to op so they are not exposed to ordinary players.

`/medieval statuscheck` is gated the same way but by the configured owner rather than a permission,
and its help line is only sent to that player - hiding the name is a convenience, not the rule, and
the check runs on execution regardless.

## Command wands

`/medieval cmdblock <mode> <item> <command>` binds a console command to a custom fishing rod,
carrot on a stick or warped fungus on a stick, and hands the item over. The item is marked with an
internal identifier in its PersistentDataContainer, so **only that exact item** activates - a
vanilla rod, or a copy without the mark, does nothing, and the check never looks at the name, so
renaming cannot forge it. The item is unbreakable and glinted.

A wand carries **two axes**, exactly like a vanilla command block - how it runs, and what powers it:

| Mode (`/medieval cmdblock mode <mode>`) | Behaviour |
| --- | --- |
| `impulse` | With the `click` trigger: the command runs once per right-click. |
| `repeating` | With the `click` trigger: right-click toggles the wand on or off; while on, the command runs every second (20 ticks), starting on the click that switched it on. The toggle is in memory only, so every restart starts with the wands off. |
| `chain` | Runs whenever **any** other wand fires or ticks - the emitting wand is the redstone that powers it. Clicking a chain wand directly also fires it. |

| Trigger (`/medieval cmdblock trigger <click|always>`) | Behaviour |
| --- | --- |
| `click` | "Needs Redstone": the wand waits for its owner - clicks, toggles, chain signals. This is the default. |
| `always` | "Always Active": the wand runs by itself on the interval from the moment the server starts, no click and no toggle, in every mode. The state is stored, so it survives restarts - unlike the repeating toggle, which is deliberately session-only. An always-active wand refuses clicks and says so. |

Both subcommands act on the wand held in the main hand, switching it in place - the identity, the
stored command and the item itself stay; only the label, lore and stored behaviour change.
Accepts `needs-redstone`/`redstone` as aliases for `click`, and `always-active`/`active` for
`always`.

Every command works, because the dispatch is the console's (`Bukkit.dispatchCommand`), the same
path the catalogue's run-command book uses - there is no permission check between the owner's
click and the server, and the dispatch surfaces in the server log like any console command.

The branch is gated by `admin.secret-owner` exactly like the catalogue: invisible in tab completion
and command listings to everyone else, checked again on execution, and **not** an op or permission
check, so it ignores op entirely. Only the owner can fire a wand too: a wand another player picks
up is inert and does not even announce itself, because advertising "this item runs console
commands" to whoever holds it would be the vulnerability.

Wand bindings are stored in the SQLite database (`cmdblock_wands` table, created on first use), so
they survive restarts; the item itself carries only its identity and mode, so re-binding with
`cmdblock set` needs no new item. Right-click is a normal interact event, which Geyser carries to
Bedrock clients unchanged, so wands work identically from touch.

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
- **The deathban rule is customizable at runtime**: `on`, `off`, `toggle`, `duration <time>`,
  `status` and `reset`, persisted through `DeathbanPolicy` in `world_state` so a change survives a
  restart while an untouched value keeps following `config.yml` - the same layering the gates use.
  The policy is unit-tested against a real SQLite file, including reopen, malformed stored values,
  reset, and a reload that must keep an override while adopting an edited file.
- **Dimension gating, wired end to end**: `/nether` and `/end` each take `open`, `close` and
  `status`; the decision is persisted in `world_state` and therefore survives a restart, a gate with
  no stored row follows `config.yml`, and the decision is published on the event bus as
  `DimensionAccessChanged` so events, GUIs and announcements react instead of being wired in.
- **Owner catalogue, wired end to end**: `/medieval statuscheck` opens a paged, tabbed chest GUI of
  every item in the server's registry, hands items out (fixed presets, right-/shift-click full
  stacks, or a chat-typed exact amount), and searches it from a chat prompt; an enchantment picker
  builds an enchanted copy of any item from chat-typed levels; the gate is a name/UUID check
  (`OwnerGate`) rather than a permission, so other operators cannot use it.
- **Combat enchantment rules, wired end to end**: quick charge on a melee weapon (sword, axe, mace,
  trident) shortens the attack cooldown through a transient, keyed attack-speed modifier that
  follows the held item; piercing on those weapons and on crossbow bolts deals damage through a
  shield by zeroing the event's blocking modifier, so armour and resistance still apply honestly;
  infinity preserves the item it is on when it is used - food, potions, thrown charges, and the
  popping totem (its own switch). The decisions live in core (`CombatRules`) and are unit-tested
  without a server; each rule is individually switchable in `config.yml` under `combat:` and is
  read through the live settings, so `/medieval reload` re-arms it.
- **Message keys are checked by the build**: `MessageCatalogueTest` scans the platform sources for
  every message key they ask for and fails, naming them, if `messages.yml` does not define one. A
  missing key is not a crash - it renders as `Missing message: <key>` in place of a button's name -
  so without this test the only detector was somebody opening the menu.
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
| Admin | `/medieval give|kingdom|siege|event|world` (the owner catalogue GUI exists - see above) |
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
