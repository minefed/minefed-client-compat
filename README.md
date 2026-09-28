# Minefed Client Compatibility

A Fabric compatibility mod for Minecraft **1.20.4** and
**PTS-Deco 4.0.0**, **Paladin's Furniture 1.5.0**, and **DragonLib 1.20.4-2.2.24**
(included in TrafficCraft 1.1.3). It requires Java 17 or newer and Fabric Loader
0.18.0 or newer. Install it alongside the official JARs on the client, and on
the dedicated server for the PFM server-tick fixes below. It adds no network
protocol, so either side works without the other. All compatibility fixes and
render changes are client-only Mixin configurations and never load on a server.

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

## Build

Use a JDK 17 or newer:

```sh
./gradlew build
```

The runtime JAR is `build/libs/minefed-client-compat-1.2.0.jar`.
The build uses Gradle 8.13 with Fabric's Mixin 0.8.7 fork and MixinExtras 0.5.0
as compile-only dependencies. Production class and method selectors are
explicit, so no Minecraft or PTS dependency, remapping task, or refmap is
needed. Fabric Loader supplies Mixin and MixinExtras at runtime. The PFM
performance Mixins compile against the small signature stubs in `src/stubs`,
which use Fabric 1.20.4 intermediary names. These stubs are never packaged.

## Validation

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
It does not include PTS-Deco, PFM or DragonLib code or assets and does not modify their distributed JARs.
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
