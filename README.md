# abled-plugins

Minecraft server-side projects.

## Contents

| Path | Description |
| --- | --- |
| [`minecraft/`](minecraft/) | **Medieval Era server system** — a Paper 26.2 plugin adding kingdoms, territory, medieval warfare, siege equipment, custom resources and equipment, defensive construction and a custom medieval world, playable from vanilla Java (1.21.11, 26.2, 26.3) and Bedrock clients through Geyser. No client mods required. |

## Building

The Minecraft project is a self-contained Gradle build with its own wrapper:

```bash
cd minecraft
./gradlew build
```

Resulting plugin jar:

```
minecraft/medieval-paper/build/libs/Medieval-<version>.jar
```

Requirements and the verified client-compatibility stack are documented in
[`minecraft/README.md`](minecraft/README.md).

## Continuous integration

- `.github/workflows/minecraft.yml` — builds, tests and uploads the plugin jar on every change to
  `minecraft/`. It needs no running server, database or external service.

## Repository layout

Projects live in their own top-level directories so adding one never disturbs another. The
Minecraft build is entirely contained in `minecraft/` and shares nothing with the repository root
except the license.

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE).
