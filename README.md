# Minefed Client Compatibility

A client-only Fabric compatibility mod for Minecraft **1.20.4** and
**PTS-Deco 4.0.0** and **Paladin's Furniture 1.5.0**. It requires Java 17 or newer
and Fabric Loader 0.18.0 or newer. Install it alongside both official JARs on the client.

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

## Build

Use a JDK 17 or newer:

```sh
./gradlew build
```

The runtime JAR is `build/libs/minefed-client-compat-1.1.0.jar`.
The build uses Gradle 8.13 and Fabric's Mixin 0.8.7 fork as a compile-only
dependency. Production class and method selectors are explicit, so no Minecraft
or PTS dependency, remapping task, or refmap is needed. Fabric Loader supplies
Mixin at runtime.

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

## Upstream and license

This project contains independently authored MIT-licensed compatibility code.
It does not include PTS-Deco or PFM code or assets and does not modify their distributed JARs.
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
