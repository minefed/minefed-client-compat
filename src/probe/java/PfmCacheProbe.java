/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import com.google.gson.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import team.minefed.mods.clientcompat.pfm.PfmCacheFingerprint;

/** Executes original and transformed official traversal and cache parsing, without writing a live cache. */
public final class PfmCacheProbe {
    private static final String GENERATOR = "com/unlikepaladin/pfm/runtime/PFMGenerator";
    private static final String CACHE = GENERATOR + "$PFMCache";
    private static final String SHA256 = "35c0dfdfa11f022b340f429f509f3bbd238134cf4224c8f11ab6e9c8f2e04fbf";

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        if (!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))))
            throw new AssertionError("Expected the official PFM 1.5.0 Fabric 1.20.4 JAR");
        Map<String, byte[]> original = new HashMap<>();
        try (JarFile zip = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(zip.entries())) if (entry.getName().endsWith(".class"))
                original.put(entry.getName().substring(0, entry.getName().length() - 6), zip.getInputStream(entry).readAllBytes());
        }
        fixtures(output.resolve("fixtures"));
        MixinCompatProbe.ProbeService.officialClasses = original;
        MixinCompatProbe.ProbeService.fixtureClasses = output.resolve("fixtures");
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("minefed-pfm-cache.mixins.json");
        var service = (MixinCompatProbe.ProbeService) MixinService.getService();
        var transformer = service.factory().createTransformer();
        MixinExtrasBootstrap.init();
        for (boolean patched : new boolean[]{false, true}) {
            Path classes = output.resolve(patched ? "fixed" : "original");
            fixtures(classes);
            for (String target : List.of(GENERATOR, CACHE)) {
                byte[] bytes = original.get(target);
                if (patched) {
                    byte[] result = transformer.transformClassBytes(target.replace('/', '.'), target.replace('/', '.'), bytes);
                    if (Arrays.equals(result, bytes)) throw new AssertionError("Mixin not applied: " + target);
                    bytes = result;
                }
                Path path = classes.resolve(target + ".class");
                Files.createDirectories(path.getParent());
                Files.write(path, isolate(bytes, target.equals(GENERATOR)));
            }
        }
        Path data = output.resolve("data");
        Files.createDirectories(data.resolve("nested"));
        Files.write(data.resolve("a.bin"), new byte[]{1, 2, 3});
        Files.write(data.resolve("empty.bin"), new byte[0]);
        Files.write(data.resolve("nested/b.bin"), new byte[]{4, 5});
        Files.writeString(data.resolve("pfmCacheData.json"), "excluded cache metadata");
        try (URLClassLoader before = loader(output.resolve("original")); URLClassLoader after = loader(output.resolve("fixed"))) {
            List<String> legacy = hashes(before, data), compact = hashes(after, data);
            check(legacy.equals(List.of("010203", "0405")), "original traversal, cache and empty-file exclusion");
            check(compact.equals(legacy.stream().map(PfmCacheFingerprint::normalizeLegacy).toList()), "digest/traversal match");
            JsonObject json = json(legacy);
            Object migrated = parse(after, json);
            Object modern = parse(after, json(compact));
            check(migrated.equals(modern) && migrated.hashCode() == modern.hashCode(), "legacy cache migration without regeneration");
            JsonObject serialized = (JsonObject) migrated.getClass().getMethod("toJson").invoke(migrated);
            check(serialized.get("folder_hash").equals(json(compact).get("folder_hash")), "compact serialization");
            for (String key : List.of("game_version", "mod_version", "mod_loader", "block_variants")) {
                JsonObject changed = new JsonParser().parse(json.toString()).getAsJsonObject();
                if (key.equals("block_variants")) { JsonArray variants = new JsonArray(); variants.add("test:changed"); changed.add(key, variants); }
                else changed.addProperty(key, "changed");
                check(!migrated.equals(parse(after, changed)), "still checks " + key);
            }
            // Same size and timestamp: contents, not mtime, decide validity.
            var modified = Files.getLastModifiedTime(data.resolve("a.bin"));
            Files.write(data.resolve("a.bin"), new byte[]{1, 2, 4});
            Files.setLastModifiedTime(data.resolve("a.bin"), modified);
            check(!compact.equals(hashes(after, data)), "same-size same-mtime edit invalidates");
            Files.write(data.resolve("a.bin"), new byte[]{1, 2, 3});
            Files.delete(data.resolve("nested/b.bin"));
            check(!compact.equals(hashes(after, data)), "removal invalidates");
            check(PfmCacheFingerprint.normalizeLegacy("not hex").equals("not hex"), "malformed legacy cache remains invalid");
            check(PfmCacheFingerprint.normalizeLegacy("00").length() == 82, "format marker prevents 32-byte legacy ambiguity");
            if (args.length > 2) benchmark(before, after, Path.of(args[2]));
        }
        System.out.println("PASS: shipped PFM cache Mixins transform official bytecode; streamed digest, ordering, empty/cache-file exclusion, same-mtime changes, removals, legacy/new JSON and version/loader/variant invalidation verified.");
    }

    private static void benchmark(ClassLoader before, ClassLoader after, Path directory) throws Exception {
        List<String> legacy = hashes(before, directory), compact = hashes(after, directory);
        check(compact.equals(legacy.stream().map(PfmCacheFingerprint::normalizeLegacy).toList()), "actual directory fingerprints");
        double[] baseline = new double[5], changed = new double[5];
        for (int i = -1; i < 5; i++) {
            long start = System.nanoTime();
            json(hashes(before, directory)).toString();
            double elapsed = (System.nanoTime() - start) / 1e6;
            if (i >= 0) baseline[i] = elapsed;
            start = System.nanoTime();
            json(hashes(after, directory)).toString();
            elapsed = (System.nanoTime() - start) / 1e6;
            if (i >= 0) changed[i] = elapsed;
        }
        Arrays.sort(baseline); Arrays.sort(changed);
        System.out.printf("PFM cache probe: %d entries, fingerprint characters %d -> %d; 5-run warm medians traversal+JSON %.3f -> %.3f ms (no disk writes; not whole-game loading).%n",
            legacy.size(), legacy.stream().mapToLong(String::length).sum(), compact.stream().mapToLong(String::length).sum(), baseline[2], changed[2]);
    }

    private static JsonObject json(List<String> values) {
        JsonObject json = new JsonObject();
        json.addProperty("game_version", "1.20.4"); json.addProperty("mod_version", "1.5.0"); json.addProperty("mod_loader", "fabric");
        JsonArray variants = new JsonArray(); variants.add("test:wood"); json.add("block_variants", variants);
        JsonArray hashes = new JsonArray(); values.forEach(hashes::add); json.add("folder_hash", hashes);
        return json;
    }

    private static Object parse(ClassLoader loader, JsonObject json) throws Exception {
        return loader.loadClass(CACHE.replace('/', '.')).getMethod("fromJson", JsonElement.class).invoke(null, json);
    }

    @SuppressWarnings("unchecked") private static List<String> hashes(ClassLoader loader, Path path) throws Exception {
        Class<?> logger = loader.loadClass("org.apache.logging.log4j.Logger");
        Object sink = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{logger}, (p, m, a) -> null);
        return (List<String>) loader.loadClass(GENERATOR.replace('/', '.')).getMethod("hashDirectory", File.class, boolean.class, logger)
            .invoke(null, path.toFile(), false, sink);
    }

    private static URLClassLoader loader(Path classes) throws Exception {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, PfmCacheProbe.class.getClassLoader());
    }

    private static byte[] isolate(byte[] bytes, boolean generator) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            public void visit(int v, int a, String n, String s, String p, String[] interfaces) { super.visit(v, a, n, null, p, new String[0]); }
            public FieldVisitor visitField(int a, String n, String d, String s, Object value) { return generator ? null : super.visitField(a, n, d, s, value); }
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if (generator && !(n.equals("hashDirectory") || n.equals("collectFiles") || n.startsWith("lambda$collectFiles") || n.contains("minefed"))) return null;
                if (!generator && n.equals("createAndWriteCacheToDisk")) return null;
                return super.visitMethod(a, n, d, s, e);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void fixtures(Path classes) throws IOException {
        Map<String, String> sources = Map.of(
            "org.apache.logging.log4j.Logger", "package org.apache.logging.log4j; public interface Logger { void error(String s); void warn(String s,Object a,Object b); }",
            "net.minecraft.class_2960", "package net.minecraft; public record class_2960(String id) { public static class_2960 method_12829(String s){return new class_2960(s);} public String toString(){return id;} }",
            "com.unlikepaladin.pfm.utilities.PFMFileUtil", "package com.unlikepaladin.pfm.utilities; public class PFMFileUtil { public enum ModLoader { INVALID,FABRIC; public String method_15434(){return name().toLowerCase();} public static ModLoader get(String s){return s.equals(\"fabric\")?FABRIC:INVALID;} } }"
        );
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path source = classes.resolveSibling(classes.getFileName() + "-src").resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent()); Files.writeString(source, entry.getValue()); args.add(source.toString());
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0) throw new AssertionError("Fixture compilation failed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
