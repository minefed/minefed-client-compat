# Minefed Client Compatibility

A client-only Fabric compatibility mod for Minecraft **1.20.4** and
**PTS-Deco 4.0.0**. It requires Java 17 or newer and Fabric Loader 0.18.0 or newer.
Install it alongside the unchanged official PTS-Deco JAR on the client.

PTS-Deco's workbench recipe reader consumes an unused Boolean after the result
item, but its writer never sends that Boolean. This consumes the next recipe's
identifier-length byte and can disconnect clients with a malformed identifier
starting with `inecraft:crafting_shaped`.

This mod redirects only that extra `readBoolean()` invocation to return `false`
without consuming bytes. It does not change the recipe payload, block registry,
or server. The exact PTS version requirement and one-invocation Mixin guard make
unexpected upstream changes fail visibly instead of applying a broad workaround.
Remove or review this compatibility mod before updating PTS-Deco.

## Build

Use a JDK 17 or newer:

```sh
./gradlew build
```

The runtime JAR is `build/libs/minefed-client-compat-1.0.0.jar`.
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

## Upstream and license

This project contains independently authored MIT-licensed compatibility code.
It does not include PTS-Deco code or assets and does not modify its distributed JAR.
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
