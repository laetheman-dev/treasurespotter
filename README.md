# Treasure Spotter

A Fabric mod for Minecraft **1.21.11** that highlights the chunk-local **(9, 9)**
surface block in every loaded chunk — the fixed column a buried treasure
structure is always anchored to within its chunk. Toggle it on/off with a
keybind you assign yourself, like any other Minecraft control.

## What it does

For every chunk the client has loaded, the mod looks straight down the column
at local coordinates `x = 9, z = 9` (world coordinates `chunkX*16 + 9`,
`chunkZ*16 + 9`) and finds the highest block that:

- is not air, **and**
- is not water (water is treated exactly like air, so ocean/river columns
  resolve to the sea floor instead of the water's surface).

That block gets a translucent gold highlight box drawn around it, client-side
only — nothing is sent to the server and no blocks are modified.

The scan is event-driven: chunks are scanned once when they load (and dropped
from the cache when they unload), so there's no per-frame recomputation. When
you turn the feature on, everything currently in view is queued and scanned a
little at a time (64 chunks/tick) so toggling it on at a high render distance
doesn't cause a hitch.

## Controls

Open **Options → Controls → Key Binds** and look for **Treasure Spotter →
Toggle Treasure Spot Highlight**. It defaults to **B**, and can be rebound to
anything (including left unbound) from that same screen.

## Building the jar

This project intentionally does **not** commit a Gradle wrapper jar (a binary
file), so build it one of these two ways:

### Option A — GitHub Actions (recommended, no local setup)

Push this repo to GitHub. `.github/workflows/build.yml` builds it
automatically on every push and pull request, and uploads the compiled jar as
a workflow artifact ("treasurespotter-jar"). Push a tag like `v1.0.0` and it
also attaches the jar to a GitHub Release.

```bash
git init
git add .
git commit -m "Treasure Spotter: initial mod"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

Then, in the repo on GitHub: **Actions** tab → the latest **Build** run →
download **treasurespotter-jar** from the run's artifacts. To get a proper
Release with the jar attached instead, tag the commit:

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Option B — Build locally

You'll need a local Gradle install (this project targets **Gradle 9.7.1**,
matching the Loom version pinned in `gradle.properties`) and a network that
can reach `maven.fabricmc.net`, `libraries.minecraft.net`, and
`piston-meta.mojang.com` (Loom downloads Minecraft and the official mappings
from these the first time you build).

```bash
gradle build
```

The compiled jar is written to `build/libs/treasurespotter-1.0.0.jar`.

If you'd rather have a `./gradlew` wrapper for convenience, generate one
locally with your own Gradle install (this only needs to be done once):

```bash
gradle wrapper --gradle-version 9.7.1
```

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11 and
   drop it in your `mods` folder — this mod depends on it.
3. Drop `treasurespotter-1.0.0.jar` in the same `mods` folder.
4. Launch the game, then bind the toggle key as described above.

## Project layout

```
build.gradle                    Gradle build script (Fabric Loom)
gradle.properties                Minecraft/Loader/Loom/Fabric API versions
settings.gradle                  Gradle project + plugin repositories
src/main/java/dev/zaz/treasurespotter/
    TreasureSpotterClient.java   The entire mod: keybind, chunk scanning,
                                  and the highlight rendering
src/main/resources/
    fabric.mod.json               Mod metadata (client-only mod)
    assets/treasurespotter/lang/en_us.json   Keybind display names
.github/workflows/build.yml      CI build + release automation
```

## Why "(9, 9)"?

Buried treasure structures are placed using a per-region, per-chunk seeded
check, and when a chunk is chosen, the structure's start position within that
chunk is fixed at local `(9, 9)` (before any surface-height adjustment for
that particular column) — which is why looking at that exact column's surface
block is a useful spot-check when treasure-map hunting.
