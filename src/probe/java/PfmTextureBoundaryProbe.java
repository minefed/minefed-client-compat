/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.service.MixinService;

/** Real PFM queue bytecode + shipped Mixin; GPU/resource endpoints are isolated fixtures. */
public final class PfmTextureBoundaryProbe {
    private static final String TARGET = "com.unlikepaladin.pfm.runtime.TextureReloadQueue";
    private static final String SHA256 = "35c0dfdfa11f022b340f429f509f3bbd238134cf4224c8f11ab6e9c8f2e04fbf";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PfmTextureBoundaryProbe <official PFM JAR> <output>");
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if (!SHA256.equals(hash)) throw new IllegalArgumentException("Expected the official PFM 1.5.0 Fabric 1.20.4 artifact");
        byte[] original;
        try (JarFile zip = new JarFile(jar.toFile())) {
            original = zip.getInputStream(zip.getJarEntry(TARGET.replace('.', '/') + ".class")).readAllBytes();
        }
        MixinCompatProbe.TARGET = TARGET;
        MixinCompatProbe.ProbeService.originalTarget = original;
        compileFixtures(output.resolve("original"));
        MixinCompatProbe.ProbeService.fixtureClasses = output.resolve("original");
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("minefed-pfm-compat.mixins.json");
        var service = (MixinCompatProbe.ProbeService) MixinService.getService();
        byte[] fixed = service.factory().createTransformer().transformClassBytes(TARGET, TARGET, original);
        if (Arrays.equals(original, fixed)) throw new AssertionError("PFM Mixin was not applied");
        for (boolean patched : new boolean[]{false, true}) {
            Path classes = output.resolve(patched ? "fixed" : "original");
            compileFixtures(classes);
            Path target = classes.resolve(TARGET.replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, isolate(patched ? fixed : original));
            URL runtime = CallbackInfo.class.getProtectionDomain().getCodeSource().getLocation();
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL(), runtime}, ClassLoader.getPlatformClassLoader())) {
                check(loader, patched, "pfm:block/oak_herringbone_planks", true, false);
                check(loader, patched, "pfm:block/oak_herringbone_planks", false, false);
                check(loader, patched, "pfm:block/other_texture", true, false);
                check(loader, patched, null, true, false);
                if (patched) check(loader, true, "pfm:block/warped_herringbone_planks", true, true);
            }
        }
        System.out.println("PASS: original defers the first upload; shipped Mixin uploads immediately only on the render thread, retains the normal queue, and restores GL binding even on upload failure.");
        System.out.println("Scope: real PFM requestReload bytecode and Sponge transformation; Minecraft/GPU fixtures. Full first-frame rendering must also be checked in game.");
    }

    private static void check(ClassLoader loader, boolean patched, String name, boolean renderThread, boolean fail) throws Exception {
        Class<?> queue = loader.loadClass(TARGET), id = loader.loadClass("net.minecraft.class_2960");
        Class<?> atlas = loader.loadClass("net.minecraft.class_1059");
        Class<?> gl = loader.loadClass("org.lwjgl.opengl.GL11");
        loader.loadClass("com.mojang.blaze3d.systems.RenderSystem").getField("renderThread").setBoolean(null, renderThread);
        atlas.getField("uploads").setInt(null, 0);
        atlas.getField("fail").setBoolean(null, fail);
        gl.getField("binding").setInt(null, 73);
        List<?> pending = (List<?>) queue.getField("list").get(null);
        pending.clear();
        boolean failed = false;
        try {
            queue.getMethod("requestReload", id).invoke(null, name == null ? null : id.getConstructor(String.class).newInstance(name));
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalStateException)) throw e;
            failed = true;
        }
        int expected = patched && renderThread && name != null && name.endsWith("_herringbone_planks") ? 1 : 0;
        if (atlas.getField("uploads").getInt(null) != expected || failed != fail)
            throw new AssertionError("Unexpected first-upload behavior for " + name);
        if (pending.size() != (name == null ? 0 : 1)) throw new AssertionError("Normal deferred cache refresh was changed");
        if (gl.getField("binding").getInt(null) != 73) throw new AssertionError("GL binding leaked");
    }

    private static byte[] isolate(byte[] input) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                return n.equals("list") ? super.visitField(a, n, d, s, v) : null;
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if (n.equals("reloadSingleSprite")) {
                    MethodVisitor m = super.visitMethod(a, n, d, s, e);
                    m.visitCode();
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/class_1059", "upload", "()V", false);
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(0, 3);
                    m.visitEnd();
                    return null;
                }
                return Set.of("<clinit>", "requestReload").contains(n) || n.contains("minefed")
                    ? super.visitMethod(a, n, d, s, e) : null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void compileFixtures(Path classes) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net.minecraft.class_2960", "package net.minecraft; public record class_2960(String id) { public String toString(){return id;} }");
        sources.put("net.minecraft.class_3300", "package net.minecraft; public interface class_3300 {}");
        sources.put("net.minecraft.class_1059", """
            package net.minecraft;
            public class class_1059 {
                public static final class_2960 field_5275 = new class_2960("minecraft:textures/atlas/blocks.png");
                public static int uploads; public static boolean fail;
                public void method_23207(){com.mojang.blaze3d.systems.RenderSystem.bindTexture(41);}
                public static void upload() throws java.io.IOException {
                    if(org.lwjgl.opengl.GL11.binding!=41)throw new AssertionError("Wrong upload atlas");
                    uploads++; if(fail)throw new java.io.IOException("Fixture upload failure");
                }
            }
            """);
        sources.put("net.minecraft.class_1060", """
            package net.minecraft; public class class_1060 {
                public class_1059 method_4619(class_2960 id) {
                    if(!id.equals(class_1059.field_5275))throw new AssertionError("Wrong atlas ID");
                    return new class_1059();
                }
            }
            """);
        sources.put("net.minecraft.class_310", """
            package net.minecraft; public class class_310 {
                public static class_310 method_1551(){return new class_310();}
                public class_3300 method_1478(){return new class_3300(){};}
                public class_1060 method_1531(){return new class_1060();}
            }
            """);
        sources.put("org.lwjgl.opengl.GL11", """
            package org.lwjgl.opengl; public class GL11 {
                public static int binding;
                public static int glGetInteger(int name){if(name!=0x8069)throw new AssertionError();return binding;}
            }
            """);
        sources.put("com.mojang.blaze3d.systems.RenderSystem", """
            package com.mojang.blaze3d.systems; public class RenderSystem {
                public static boolean renderThread=true;
                public static boolean isOnRenderThread(){return renderThread;}
                public static void bindTexture(int id){org.lwjgl.opengl.GL11.binding=id;}
            }
            """);
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path path = classes.resolveSibling(classes.getFileName() + "-src").resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
            args.add(path.toString());
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0)
            throw new AssertionError("Fixture compilation failed");
    }
}
