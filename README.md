# Minefed Client Compatibility

A Fabric compatibility mod for Minecraft **1.20.4** and
**PTS-Deco 4.0.0**, **Paladin's Furniture 1.5.0**, **TrafficCraft 1.20.4-1.1.3**,
and its bundled **DragonLib 1.20.4-2.2.24**. It requires Java 17 or newer and
Fabric Loader 0.18.0 or newer. Install it alongside the official JARs on the
client, and on the dedicated server for the PFM and TrafficCraft server-side
changes below. It adds no network protocol, so either side works without the
other. All compatibility fixes and render changes are client-only Mixin
configurations and never load on a server.

PTS-Deco's workbench recipe reader consumes an unused Boolean after the result
item, but its writer never sends that Boolean. This consumes the next recipe's
identifier-length byte and can disconnect clients with a malformed identifier
starting with `inecraft:crafting_shaped`.

This mod redirects only that extra `readBoolean()` invocation to return `false`
without consuming bytes. It does not change the recipe payload, block registry,
or server. The exact PTS version requirement and one-invocation Mixin guard make
unexpected upstream changes fail visibly instead of applying a broad workaround.
Remove or review this compatibility mod before updating PTS-Deco.

PFM lazily generates eleven herringbone plank textures while emitting their
first item quads, but uploads them at the next client tick. The first frame
therefore displays purple/black placeholders. The PFM Mixin immediately uploads
only the newly generated herringbone sprite when already on the render thread,
and restores the previous GL texture binding in a `finally` block. The original
queue remains intact so PFM still persists its cache and refreshes world meshes
in one normal tick batch. Other textures and worker-thread requests retain their
existing behavior. The version dependency and single-injection guard require
review before upgrading PFM.

## PFM performance

Version 1.2.0 removes work in the official PFM 1.5.0 classes that has no
observable effect. Each change keeps PFM's own code path for every other case.

- **Stove and stovetop updates (server).** With `foodPopsOffStove` disabled
  (the default), a finished item without a campfire recipe stays on the burner.
  PFM then stores the same stack again and calls `sendBlockUpdated` on every
  tick, which sends a block update and the full block-entity NBT to every
  tracking player. The Mixin skips only that identical store and its update:
  the recipe lookup still runs, and conversions, the pop-off branch, and
  `setChanged` behave as before. Items, block state, and everything PFM renders
  therefore reach clients exactly as before. The skipped packet otherwise
  refreshes only timers such as oven fuel and cooking progress. Clients do not
  display them from block-entity data, and PFM leaves them stale on clients
  during ordinary cooking.
- **Idle microwave (server).** A microwave holding an item looks up its smoking
  recipe every tick but uses the result only while running. When it is idle, the
  Mixin returns no recipe instead. The vanilla lookup only filters recipes with
  `matches`, and the running flag cannot change between the lookup and its use.
- **Empty plate, trash can, and microwave renderers (client).** When the
  displayed stacks are empty, `renderStatic` draws nothing, so these renderers
  now return before their balanced pose push/pop, light, registry, and recipe
  work. They still set PFM's public `itemStack` field to the value PFM would
  leave. A trash can with items reads its light once per frame instead of nine
  times at the same position. A running microwave reuses its first recipe lookup
  for the other two in the same condition.

Table/desk shape keys, PFM's own item-renderer Mixins, and shower sounds are
unchanged. They cannot be patched cleanly from here, or their result would
differ. These Mixins use MixinExtras 0.5.0, which Fabric Loader 0.18.4 bundles.

## TrafficCraft performance

Version 1.3.0 wraps a few calls in the official TrafficCraft 1.20.4-1.1.3 and
DragonLib 1.20.4-2.2.24 classes. It copies no TrafficCraft code: each Mixin
calls TrafficCraft's own method, or keeps the result that method returned.

- **Unchanged traffic-light updates (server).** DragonLib's `notifyUpdate`
  calls `setChanged` and queues a block update plus the full block-entity NBT
  for every tracking player. An unpowered traffic light calls
  `setPowered(false)` on every neighbour change, and redstone-triggered lights
  also call `stopSchedule`. Schedules may also call `enableOnlyColors` with the
  colors already shown. When such a call leaves its fields as they were (the
  same colors, compared object by object), the Mixin still calls `setChanged`.
  Chunk saving and comparator updates therefore stay the same; only the
  redundant packets are skipped. Every change of a field clients read is still
  sent by the method that makes it. Only the internal schedule timer changes
  without a packet, and clients never read it.
- **Block shapes (both sides).** `TrafficLightBlock`, `TrafficSignPostBlock`,
  and `RoadSaltBlock` build their outline shape from the block state alone, with
  up to seven `Shapes.or` calls or a new box per query. The first shape
  TrafficCraft returns for each of its own states is reused. Voxel shapes are
  immutable, so later callers get an equal shape.
- **Sign texture resets (server).** A new sign texture sends a reset packet to
  every player in the dimension. Its client handler only resets a sign found at
  that position. The packet now goes only to the players that vanilla sends
  that chunk's block updates to. Other clients never have that sign loaded.
- **Traffic-light bulbs (client).** Each bulb of each light searched the model
  list with a stream every frame. The model that search returns is remembered
  for its icon and color, and later bulbs draw that same model object through
  TrafficCraft's own render method.

Traffic-light colors still use the full block-entity update: a smaller custom
packet would leave other client fields to other code paths. Sign background
textures are still rebuilt, because they depend on the current resource pack.
The server's sign texture files are still read on each request, because they
can also be changed outside the game. Schedule change checks and bulb texture
atlases are unchanged.

## Block atlas memory (client)

Version 1.4.0 stores block atlas sprites more compactly just before the atlas is
stitched (`SpriteLoader.stitch`, block atlas only). The rules live in
`AtlasSpriteOptimizer`; every other atlas and every other sprite is unchanged.

- **Exact enlargements.** A static sprite in which every f×f block has one colour
  (for example a 1024×1024 single-colour guardrail part) is stored at 1/f size.
  Every texel samples the same colour as before.
- **Size cap.** Static sprites larger than 512 pixels are halved with a
  premultiplied box filter while both sides stay multiples of 16. This changes
  pixels (City Craft's 2048×2048 guardrails compare at about 50 dB PSNR, its
  trash bin at about 35 dB). The `msd` namespace, whose textures carry text, is
  excluded.
- **Mipmap alignment.** Vanilla lowers the mipmap level of the whole atlas to the
  largest power of two dividing every sprite side, so a single 21×21 icon turned
  mipmaps off for every block. Sprites below the alignment are enlarged with
  nearest neighbour (at most 4×, at most 512 pixels; the full-size texels are
  unchanged) or otherwise resampled to the nearest multiple of 16. With the
  default mipmap level, distant blocks are filtered as vanilla intends again.

With the Minefed 1.20.4 pack the block atlas sprites shrink from about 61 to
27 megapixels, and the atlas from 16384×8192 without mipmaps (512 MiB of VRAM)
to 8192×8192 with four mipmap levels (about 341 MiB); the sprite images kept in
native memory shrink accordingly. Settings are read from
`config/minefed-atlas.properties` (`exactDownscale`, `mipmapAlignment`,
`maxStaticSize`, `sizeCapExcludedNamespaces`); system properties prefixed with
`minefed.atlas.` take precedence. `maxStaticSize=0` disables the size cap.

## MCEF start-up (client)

MCEF 2.1.6 starts Chromium, including its GPU and utility processes, when the
first screen opens. The optional `minefed-mcef-lazy` Mixin defers
`MCEF.initialize()` until something first uses the MCEF API (every such entry
point calls `assertInitialized()`). Players who never see a web display do not
start Chromium; the first web display appears once Chromium has started, about
1–2 seconds later. The native download at game start is unchanged. The
configuration is not required, so the mod still loads without MCEF.

## Build

Use a JDK 17 or newer:

```sh
./gradlew build
```

The runtime JAR is `build/libs/minefed-client-compat-1.4.0.jar`.
The build uses Gradle 8.13 with Fabric's Mixin 0.8.7 fork and MixinExtras 0.5.0
as compile-only dependencies. Production class and method selectors are
explicit, so no Minecraft or PTS dependency, remapping task, or refmap is
needed. Fabric Loader supplies Mixin and MixinExtras at runtime. The PFM and
TrafficCraft performance Mixins compile against the small signature stubs in
`src/stubs`, which use Fabric 1.20.4 intermediary names. These stubs are never
packaged.

## Validation

`verifyAtlasSprites` checks the block atlas rules on synthetic images without any
fixture. With `-PatlasModsDir=<directory of mod JARs>` it also runs the rules on
every block atlas texture of those JARs, checks that every result is aligned to
16 pixels and keeps its animation frames, and compares every texel of the
results marked exact with the original texel.

The reusable probes in `src/probe` require a local copy of the official artifact
identified below. They check its SHA-256 and never download or redistribute it.
Use a JDK 17 or newer, since the probe compiles its own small value/buffer fixtures:

```sh
./gradlew verifyPtsCompatibility -PptsJar=/path/to/PTS-Deco-4.0.0-Fabric1.20.4.jar
```

On Windows, use `gradlew.bat` and quote the complete `-PptsJar=...` argument when
the path contains spaces. The task first reproduces the original failure, then
loads the built JAR's actual Mixin configuration with Sponge Mixin 0.8.7 in the
client environment. It transforms the original PTS serializer's network methods
and round-trips recipes with 0, 1, and 3 material entries followed by a
`minecraft:crafting_shaped` identifier. Generated fixtures stay in
`build/recipe-probe`; probe code and dependencies are excluded from the mod JAR.

The unmodified reader consumes one byte beyond each recipe. After the real Mixin
transformation, exactly one redirect replaces the erroneous call, the read
position equals the written recipe length, and the following identifier remains
intact in all three cases. This also verifies the `@Coerce Object` handler on the
JVM. This isolated check does not establish a successful live-server login.

The PFM probe uses the real upstream `requestReload` bytecode and the shipped
Mixin configuration, with small resource/GPU fixtures at the upload boundary:

```sh
./gradlew verifyPfmCompatibility -PpfmJar=/path/to/paladin-furniture-mod-1.5.0-fabric-mc1.20.4.jar
```

It reproduces the deferred first upload, then checks immediate render-thread
upload, unchanged worker-thread/other-texture/null behavior, retained queue,
and GL binding restoration on both success and upload failure. This complements
the Minecraft 1.20.4 / Loader 0.18.4 cold creative-inventory sweep: all 18,970
stacks were drawn before model prewarming; the eleven first-frame placeholders
disappeared. Pixel matches from actual purple items were inspected separately.

`verifyPfmCompatibility` first runs `verifyPfmPerformance` with the same JAR.
That probe checks the shipped configurations: the performance config loads on
both sides, and every other config stays client-only. Every Minecraft or PFM
member the new Mixins call must also appear, with the same owner and
descriptor, in the official PFM bytecode. The probe applies the Mixins to the
official stove, stovetop, microwave, and renderer classes with Sponge Mixin and
MixinExtras. It then runs original and transformed tick code on fixtures. The
event logs match except for the skipped identical stove stores/updates and
idle microwave lookups. Renderers stop only for empty contents. Packet volume
and frame time still need an in-game measurement.

The TrafficCraft probe works the same way with the official TrafficCraft JAR:

```sh
./gradlew verifyTrafficCraftPerformance -PtrafficcraftJar=/path/to/trafficcraft-fabric-1.20.4-1.1.3.jar
```

It checks the JAR and its bundled DragonLib by SHA-256. It then checks the
TrafficCraft bytecode itself. The cached `getShape` methods read only their
state and constant shapes. The bulb model list is filled once, and key equality
compares only the icon and color. Every traffic-light method that changes
client-read data sends its own update. The sign reset handler only resets a
sign it finds. The Mixins are applied with Sponge Mixin and MixinExtras, and the
real traffic-light, sign, shape, and bulb code runs on fixtures. Traffic-light
event logs match except for packets of unchanged calls. Shapes match for all
419 fixture states and are built once. Resets reach exactly the chunk's
players. Bulb draws, lights, and failures match for every icon/color pair,
with one list search per pair. A dedicated-server start with these Mixins,
placing and updating a traffic light, loaded all server targets without errors.

## Upstream and license

### TrafficCraft / DragonLib Wikipedia requests

Version 1.1.1 also identifies DragonLib's optional Wikidata API requests with a
descriptive User-Agent and contact URL, following the
[Wikimedia API policy](https://www.mediawiki.org/wiki/API:Etiquette#The_User-Agent_header).
Java's generic agent received HTTP 403 for the two TrafficCraft article IDs;
the identified request returned valid sitelinks for both. Only the single
`URL.openStream` call in the pinned WikipediaArticle loader is redirected.
HTTPS Wikidata requests get 10-second connection/read timeouts; other URLs and
the existing exception/fallback behavior are preserved. No global HTTP property
or official dependency JAR is changed.

This independently authored MIT code lives here because the pack retains the
official TrafficCraft bundle under its reviewed distribution policy. Registering
the redirect only in a rebuilt TrafficCraft JAR would not fix that selection.
Do not also register an equivalent TrafficCraft redirect in the same profile.
The parent repository's `audit.verifyWikipedia` game probe checks both live
article language maps; it is optional when running offline texture tests.

### Notices

This project contains independently authored MIT-licensed compatibility code.
It does not include PTS-Deco, PFM, TrafficCraft or DragonLib code or assets and does not modify their distributed JARs.
The Gradle wrapper scripts retain their upstream Apache-2.0 notices.

The affected official artifact is
[PTS-Deco 4.0.0 for Fabric 1.20.4](https://modrinth.com/version/etl5lvBR):

```text
PTS-Deco-4.0.0-Fabric1.20.4.jar
SHA-256 aa75d6e205c34a063fead0dc5cbd564fcbca640172db718e22ac17ebdcc64919
```

[PTS-Deco](https://www.curseforge.com/minecraft/mc-mods/pts-deco) is separately
licensed All Rights Reserved, with permission to include the official mod in
modpacks. Its terms do not change the MIT license of this independent add-on.

The PFM fixture is [Paladin's Furniture 1.5.0 Fabric 1.20.4](https://modrinth.com/version/CFrrGcF0):

```text
paladin-furniture-mod-1.5.0-fabric-mc1.20.4.jar
SHA-256 35c0dfdfa11f022b340f429f509f3bbd238134cf4224c8f11ab6e9c8f2e04fbf
```

PFM's LGPL-3.0 source and PolyForm Shield visual assets retain their upstream
terms. The independently authored compatibility code copies neither.

The TrafficCraft fixture is [TrafficCraft 1.20.4-1.1.3 Fabric](https://modrinth.com/mod/Y1PXWvWn/version/rzrY1Buf),
including DragonLib 1.20.4-2.2.24 as a nested JAR:

```text
trafficcraft-fabric-1.20.4-1.1.3.jar
SHA-256 ddf1d434be7eee0ab7ff67ba4712cb213900bc10c43776cc52dddc40697a0a58
META-INF/jars/dragonlib-fabric-1.20.4-2.2.24.jar
SHA-256 c53a94bb2d1e37e0146c37f018153394697221b40cf60ae4f4edb897a00a530f
```

TrafficCraft is licensed GPL-3.0 and keeps its upstream terms. These Mixins
only wrap its methods and copy none of its code.
