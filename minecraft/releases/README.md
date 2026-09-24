# Built plugin artifacts

Convenience copies of the built plugin, committed so a server owner can download the jar directly
without building anything or being signed in to GitHub Actions.

| File | Built from |
| --- | --- |
| `Medieval-0.1.0-SNAPSHOT.jar` | the `main` commit that added the hidden owner catalogue |

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
