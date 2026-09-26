# Built plugin artifacts

Convenience copies of the built plugin, committed so a server owner can download the jar directly
without building anything or being signed in to GitHub Actions.

| File | Built from |
| --- | --- |
| `Medieval-0.1.0-SNAPSHOT.jar` | the `main` commit that added the combat enchantment rules: quick charge that really speeds up a melee attack, piercing that goes through shields, and infinity that preserves consumables |What that jar offers beyond the owner catalogue and the deathban commands (a paged, tabbed chest GUI with a chat-typed search, chat-typed exact amounts, an enchantment picker, a chat-typed gamemode switcher, and a chat-typed console-command runner on the Admin tab; `check|set|clear|list|on|off|toggle|duration|status|reset`): three combat enchantment rules, each switchable in `config.yml` under `combat:` and re-armed by `/medieval reload`, and the owner-only `/medieval cmdblock` command that binds a console command to a custom fishing rod or carrot-on-a-stick - impulse runs once per right-click, repeating toggles on and off and runs every second while on, chain fires whenever any other wand fires (the triggering wand acting as the redstone), and `cmdblock mode|trigger` switches a held wand between modes or between needs-redstone and always-active in place.

- **Quick charge on a melee weapon** (sword, axe, mace, trident) shortens the attack cooldown by about a tenth per level, capped at nine tenths so no catalogue level turns a blade into a machine gun. Applied as a transient attack-speed attribute modifier, so it never survives a crash and follows the held weapon.
- **Piercing goes through shields**: an attack from a qualifying weapon, or a piercing crossbow bolt, deals its damage to a blocking defender. Blocking against anything else works exactly as vanilla.
- **Infinity preserves the item it is on**: a wind charge is not spent when thrown, an ender pearl survives its throw, a golden apple or a steak is not eaten, a potion is not drunk away (bowl, bottle and bucket come back with the stack), and a totem of undying comes back a tick after it pops. Arrows keep their vanilla infinite-bow behaviour.

## Install

1. Drop `Medieval-0.1.0-SNAPSHOT.jar` into your server's `plugins/` directory.
2. Run **Paper 26.2** on a **Java 25** runtime. The jar declares `api-version: '26.2'`.
3. First start needs internet: the SQLite driver is not bundled - `plugin.yml` lists it under
   `libraries` and the server downloads it from Maven Central once.
4. Optional, never required: Geyser-Spigot + floodgate (Bedrock players), ViaVersion + ViaBackwards
   (clients newer/older than 26.2). Without them the plugin runs the Java-only path and logs which
   capability is missing.

## This is a snapshot, not the source of truth

The jar here is a build **output**. It can be older than the source next to it, and it is not what CI
verifies.

- The authoritative build is `.github/workflows/minecraft.yml`, which runs `./gradlew build` for
  every change under `minecraft/` and uploads the same artifact.
- To rebuild from source:

  ```bash
  cd minecraft
  ./gradlew build          # compiles, runs the unit tests and packages the plugin
  ```

  The output lands in `medieval-paper/build/libs/`, which is git-ignored - it is copied here
  deliberately, by hand, when a release is worth publishing.

Do not edit anything in this directory by hand. If this jar and `minecraft/README.md` disagree about
what is implemented, the README describes the source and this file may simply be behind.
