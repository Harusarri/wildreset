<div align="center">
  <img src="src/main/resources/assets/wildreset/icon.png" alt="WildReset Logo" width="160" height="160" />
  <h1>WildReset</h1>
  <p><strong>Zero-Downtime / No-Limbo Wilderness Chunk Reset Engine for Minecraft 26.3 (Fabric)</strong></p>
</div>

> [!NOTE]
> **AI-Generated Project ("AI Slop" Disclosure)**
> This mod—including its architecture, multi-stage worldgen & chunk swap engine, mixins, and documentation—was generated and iteratively tuned with AI assistance. Review the source code and benchmark on a staging environment before deploying to production servers.

---

## What It Does

**WildReset** is a server-side Fabric mod for **Minecraft 26.3** designed to periodically reset unprotected wilderness chunks around spawn **live on a running server**—with **zero server restarts**, **no Velocity/Limbo evacuation**, and **100% preservation of protected regions**.

WildReset works **100% standalone** via built-in `/wildreset chunk` protection commands, integrates with external protection plugins via **`WildResetApi`**, and automatically detects **YAWP (Yet Another World Protector)** regions when installed.

---

## Key Features

- **Zero-Downtime / No-Limbo Live Chunk Swap**:
  - **Stage 1 (Multi-Threaded Worker Pool)**: Assembles clean seed-generated chunk templates off-thread, performs underground ore permutation, extracts player containers, and calculates safe surface coordinates (`True Ground`) without stalling the server tick loop.
  - **Stage 2 (Tick-Budgeted Main-Thread Commit)**: Applies prepared chunks within a strict per-tick budget (`<= 12ms/tick`), snaps in-zone players vertically to safe ground first, swaps chunk sections and heightmaps second, synchronously relights via **ScalableLux (Starlight)**, and unfreezes players third.
- **Full-Radius Season Lazy On-Load Reset**:
  - Unloaded chunks inside the reset radius are tagged by season and automatically reset on `CHUNK_LOAD` / `FULL_CHUNK_STATUS_CHANGE` before chunk packets are sent to players (via `ChunkHolder#addSendDependency`), enabling instant resets across large radii (e.g., `2500+` blocks) without pre-loading the entire world.
- **Standalone Chunk Protection, Public API & Optional YAWP Integration**:
  - **Standalone Mode**: Protect individual chunks or rectangular chunk ranges directly via `/wildreset chunk` (persisted in `config/wildreset_regions.json`), plus a configurable outer buffer halo (`+1` chunk by default).
  - **Public Developer API (`WildResetApi` & `WildResetRegionProvider`)**: External claim/region mods can register custom protected regions or trigger targeted chunk resets programmatically.
  - **Optional YAWP Support**: Automatically detects and excludes active YAWP regions when YAWP is present, while running 100% standalone when YAWP is absent.
- **+10-Chunk Quarantine & In-Zone Time Freeze**:
  - During the brief active swap window, blocks external entry, portals, and ender pearls into the active reset envelope while sending vanilla `ClientboundTickingStatePacket` freeze states to players standing inside target chunks to prevent fall damage or container duping.
- **Container & Tamed Pet Surface Evacuation + 72-Hour Lock**:
  - Non-empty player containers (Chests, Barrels, Shulker Boxes, Furnaces, etc.) and tamed **Wolves & Cats** in reset chunks are automatically evacuated onto `True Ground` (never on leaves or logs).
  - Double chests are compacted into single chests and protected for **72 hours** against non-owner opening, breaking, hopper extraction, explosions, pistons, and copper golems. Unclaimed evacuated chests from previous seasons automatically expire on the next season reset.
- **Seamless Cross-Chunk Worldgen & Structure Restoration**:
  - Uses a 5x5 (`radius=2`) proto-chunk worldgen neighborhood (`STRUCTURE_STARTS` -> `STRUCTURE_REFERENCES` -> `BIOMES` -> `NOISE` -> `SURFACE` -> `CARVERS` -> `FEATURES`) with proper `FluidTicks` and `BlockTicks` scheduling so multi-chunk structures (villages), cross-border trees, amethyst geodes, and cave water/lava flows generate without chunk-border clipping or terrain fusion.
- **Anti-X-Ray Underground Ore Permutation (`Y < 16`)**:
  - Strips default seed diamond veins below `Y = 16` and probabilistically converts Lapis Lazuli (`5.0%`) and Redstone (`1.5%`) ores into Diamond Ore using a per-season random seed, defeating seed-cracker X-Ray abuse after every reset.
- **Multi-Language Support (`auto` / `en_us` / `ko_kr`)**:
  - Automatically detects each player's client language setting (`en_us` or `ko_kr`) in `auto` mode—including per-player localized BossBars, ActionBars, and Chat broadcasts—or allows admins to force a server-wide language via `/wildreset lang`.

---

## Requirements

- **Minecraft**: `26.3` (Unobfuscated)
- **Mod Loader**: Fabric Loader `>= 0.16.0` (tested on `0.18.4`)
- **Fabric API**: Required
- **Java**: `25` or newer
- **Optional Integrations**:
  - **YAWP (Yet Another World Protector)**: Optional; automatically detected when installed
  - **ScalableLux / C2ME / Lithium / Polymer**: Native compatibility hooks included

---

## Commands

Requires OP / Gamemaster permission (`permission level >= 2`):

| Command | Description |
| :--- | :--- |
| `/wildreset status` | Display current spawn cuboid, protected+halo chunk count, protected evacuated chests, ore randomizer rates, language mode, and engine status |
| `/wildreset run` | Start a full-radius wilderness reset with countdown, BossBar progress, and season lazy on-load activation |
| `/wildreset run <radiusChunks>` | Run a targeted reset within `<radiusChunks>` around the configured spawn center |
| `/wildreset chunk` or `/wildreset chunk list [page]` | Open the interactive paginated list of standalone protected chunks/ranges (`[TP]`, `[Remove]`, `[+ Toggle Current Chunk]`) |
| `/wildreset chunk toggle` | Toggle standalone protection for the chunk you are currently standing in |
| `/wildreset chunk add [radius]` | Protect your current chunk (`radius=0`) or a `(2r+1)x(2r+1)` chunk range around you (`0..16`) |
| `/wildreset chunk add <chunkX> <chunkZ>` | Protect a specific chunk by coordinates |
| `/wildreset chunk add <fromX> <fromZ> <toX> <toZ>` | Protect a rectangular chunk range from `(fromX, fromZ)` to `(toX, toZ)` |
| `/wildreset chunk remove [chunkX] [chunkZ]` | Remove standalone protection covering your current chunk or `(chunkX, chunkZ)` |
| `/wildreset chunk reset [radius]` | Immediately reset your current chunk (or `(2r+1)x(2r+1)` chunks around you, `0..8`) |
| `/wildreset cache <radiusChunks>` | Capture currently loaded chunks within `<radiusChunks>` into `world/wildreset_cache/` as clean baseline templates |
| `/wildreset setcenter <x> <z>` | Update and save the spawn reset center coordinates (`spawnCenterX`, `spawnCenterZ`) |
| `/wildreset setradius <blocks>` | Update and save the square reset radius in blocks (`16`–`50000`) |
| `/wildreset lang [auto\|en_us\|ko_kr]` | View or switch the localization language mode (`auto` detects each player's client language) |
| `/wildreset reload` | Reload `config/wildreset.json` and `config/wildreset_regions.json` |

---

## Public Developer API (`kr.harusarri.wildreset.api`)

External mods can integrate with WildReset without any hard dependency on YAWP using `WildResetApi` and `WildResetRegionProvider`:

```java
import kr.harusarri.wildreset.api.WildResetApi;
import kr.harusarri.wildreset.api.WildResetRegionProvider;

// 1. Register a custom region provider
WildResetApi.registerRegionProvider(level -> List.of(
    new WildResetRegionProvider.ProtectedRegion(
        level.dimension().identifier().toString(),
        "spawn_town",
        "Spawn Town",
        -128, -128, 128, 128
    )
));

// 2. Manage standalone protected chunks programmatically
WildResetApi.addProtectedChunk("minecraft:overworld", 0, 0);
WildResetApi.addProtectedChunkRange("minecraft:overworld", -2, -2, 2, 2);

// 3. Query protection or trigger targeted chunk resets
boolean isSafe = WildResetApi.isProtectedChunk(overworld, 0, 0);
WildResetApi.startTargetedChunkReset(server, List.of(new ChunkPos(10, 10)));
```

---

## Configuration

WildReset generates its main configuration at `config/wildreset.json` and standalone protected chunk policies at `config/wildreset_regions.json`:

```json
{
  "enabled": true,
  "language": "auto",
  "dimension": "minecraft:overworld",
  "spawnCenterX": 0,
  "spawnCenterZ": 0,
  "cuboidRadiusBlocks": 2500,
  "protectedBufferHaloChunks": 1,
  "quarantineOuterBufferChunks": 10,
  "newbieWarningEnabled": true,
  "newbieWarningActionBarEnabled": true,
  "newbieWarningChatEnabled": true,
  "newbieWarningMaxCount": 4,
  "newbieWarningCooldownSeconds": 2,
  "chestProtectionHours": 72,
  "convertDoubleChestsToSingle": true,
  "expireUnclaimedChestsNextSeason": true,
  "evacuateTamedDogsAndCats": true,
  "oreRandomizerEnabled": true,
  "oreRandomizerMaxY": 16,
  "lapisToDiamondChance": 0.05,
  "redstoneToDiamondChance": 0.015,
  "workerThreads": 4,
  "mainThreadTickBudgetMs": 12,
  "countdownSeconds": 3
}
```

---

## Build from Source

```bash
./gradlew build
```

Compiled JAR artifacts will be output to `build/libs/`.

---

## Credits & License

- **Author**: [Harusarri](https://github.com/Harusarri)
- **License**: Released under the [MIT License](LICENSE).
