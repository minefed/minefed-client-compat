/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;
import org.objectweb.asm.*;

/** Executes the actual PTS 4.0.0 reader/writer bytecode with minimal Minecraft stubs. */
public final class PtsRecipeBoundaryProbe {
    private static final String SERIALIZER = "com/nik123123555/ptsdeco/crafting/WorkbenchContructingRecipe$Serializer";
    private static final String RECIPE = "com.nik123123555.ptsdeco.crafting.WorkbenchContructingRecipe";
    private static final String NEXT_ID = "minecraft:crafting_shaped";
    private static final String EXPECTED_SHA256 = "aa75d6e205c34a063fead0dc5cbd564fcbca640172db718e22ac17ebdcc64919";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PtsRecipeBoundaryProbe <PTS jar> <output directory>");
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        System.out.println("jar.sha256=" + hash);
        if (!hash.equals(EXPECTED_SHA256)) throw new IllegalArgumentException("Expected the unchanged official PTS-Deco 4.0.0 Fabric 1.20.4 JAR");
        byte[] original;
        try (JarFile zip = new JarFile(jar.toFile())) {
            original = zip.getInputStream(zip.getJarEntry(SERIALIZER + ".class")).readAllBytes();
        }
        for (boolean fixed : new boolean[]{false, true}) {
            Path classes = output.resolve(fixed ? "fixed-probe-classes" : "original-probe-classes");
            compileStubs(classes);
            Path target = classes.resolve(SERIALIZER + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, isolateSerializer(original, fixed));
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                for (int ingredients : new int[]{0, 1, 3}) runCase(loader, fixed, ingredients);
            }
        }
        System.out.println("PASS: original overreads exactly one byte in all 3 cases; removing only readBoolean preserves the next recipe ID.");
        System.out.println("Scope: actual PTS Serializer read/write methods; isolated Minecraft value/buffer stubs, not a full Fabric/server integration test.");
    }

    private static byte[] isolateSerializer(byte[] source, boolean fixed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] booleanReads = {0};
        new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                super.visit(version, access, name, null, parent, new String[0]);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) { return null; }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // Keep the original executable reader/writer, constructor and their lambda bodies.
                // Codec initialization and RecipeSerializer bridges require the full game and are irrelevant here.
                if (!Set.of("<init>", "fromNetwork", "toNetwork", "lambda$fromNetwork$8", "lambda$toNetwork$9").contains(name)) return null;
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, null, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                        if (name.equals("fromNetwork") && owner.equals("net/minecraft/class_2540") && method.equals("readBoolean") && desc.equals("()Z")) {
                            booleanReads[0]++;
                            if (fixed) {
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ICONST_0);
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, iface);
                    }
                };
            }
        }, 0);
        if (booleanReads[0] != 1) throw new AssertionError("Expected exactly one readBoolean, got " + booleanReads[0]);
        return writer.toByteArray();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runCase(ClassLoader loader, boolean fixed, int ingredients) throws Exception {
        Class<?> buffer = loader.loadClass("net.minecraft.class_2540");
        Class<?> stack = loader.loadClass("net.minecraft.class_1799");
        Class<?> listType = loader.loadClass("net.minecraft.class_2371");
        Class<?> ingredient = loader.loadClass("com.nik123123555.ptsdeco.crafting.StackedIngredient");
        Class<?> recipeType = loader.loadClass(RECIPE);
        Object serializer = loader.loadClass(SERIALIZER.replace('/', '.')).getConstructor().newInstance();
        Object list = listType.getConstructor().newInstance();
        for (int i = 0; i < ingredients; i++) ((List)list).add(ingredient.getConstructor(int.class).newInstance(i + 1));
        Object recipe = recipeType.getConstructor(listType, stack).newInstance(list, stack.getConstructor(int.class, int.class).newInstance(314, 2));
        Object writeBuffer = buffer.getConstructor().newInstance();
        serializer.getClass().getMethod("toNetwork", buffer, recipeType).invoke(serializer, writeBuffer, recipe);
        int recipeBytes = ((byte[])buffer.getMethod("bytes").invoke(writeBuffer)).length;
        buffer.getMethod("writeString", String.class).invoke(writeBuffer, NEXT_ID);
        buffer.getMethod("writeString", String.class).invoke(writeBuffer, "yuushya:wood/raw_cherry_table");
        buffer.getMethod("padding", int.class).invoke(writeBuffer, 200);
        Object readBuffer = buffer.getConstructor(byte[].class).newInstance(buffer.getMethod("bytes").invoke(writeBuffer));
        Object decoded = serializer.getClass().getMethod("fromNetwork", buffer).invoke(serializer, readBuffer);
        int readerIndex = (int)buffer.getMethod("readerIndex").invoke(readBuffer);
        String nextId = (String)buffer.getMethod("readString").invoke(readBuffer);
        int decodedCount = ((List)recipeType.getField("materials").get(decoded)).size();
        int expectedOverread = fixed ? 0 : 1;
        if (decodedCount != ingredients || readerIndex - recipeBytes != expectedOverread) throw new AssertionError("Boundary mismatch");
        if (fixed && !nextId.equals(NEXT_ID)) throw new AssertionError("Fixed serializer corrupted next ID: " + nextId);
        if (!fixed && (!nextId.startsWith("inecraft:crafting_shaped") || nextId.length() != 109)) throw new AssertionError("Original did not reproduce screenshot signature");
        System.out.printf("mode=%s ingredients=%d recipeBytes=%d readerIndex=%d overread=%d nextStringLength=%d nextStringPrefix=%s%n",
            fixed ? "fixed" : "original", ingredients, recipeBytes, readerIndex, readerIndex-recipeBytes, nextId.length(),
            nextId.substring(0, Math.min(25, nextId.length())).replace("\u001b", "\\u001b"));
    }

    private static void compileStubs(Path classes) throws IOException {
        Files.createDirectories(classes);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net.minecraft.class_1799", """
            package net.minecraft;
            public final class class_1799 { public final int id,count; public class_1799(int id,int count){this.id=id;this.count=count;} }
            """);
        sources.put("net.minecraft.class_2371", """
            package net.minecraft;
            public class class_2371 extends java.util.ArrayList<Object> {
              public static class_2371 method_10213(int count,Object value){class_2371 list=new class_2371();for(int i=0;i<count;i++)list.add(value);return list;}
            }
            """);
        sources.put("net.minecraft.class_2540", """
            package net.minecraft;
            public final class class_2540 {
              private final java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
              private byte[] input; private int cursor;
              public class_2540(){} public class_2540(byte[] bytes){input=bytes;}
              private int read(){if(cursor>=input.length)throw new IndexOutOfBoundsException();return input[cursor++]&255;}
              public int readerIndex(){return cursor;} public byte[] bytes(){return out.toByteArray();}
              public class_2540 method_53002(int value){for(int shift=24;shift>=0;shift-=8)out.write(value>>>shift);return this;}
              public int readInt(){return (read()<<24)|(read()<<16)|(read()<<8)|read();}
              public boolean readBoolean(){return read()!=0;}
              private void writeVarInt(int value){while((value&~127)!=0){out.write((value&127)|128);value>>>=7;}out.write(value);}
              private int readVarInt(){int value=0,shift=0,b;do{b=read();value|=(b&127)<<shift;shift+=7;}while((b&128)!=0);return value;}
              public class_2540 method_10793(class_1799 stack){out.write(1);writeVarInt(stack.id);out.write(stack.count);out.write(0);return this;}
              public class_1799 method_10819(){if(!readBoolean())return new class_1799(0,0);int id=readVarInt(),count=read();if(read()!=0)throw new IllegalStateException("stub expects empty NBT");return new class_1799(id,count);}
              public void writeString(String text){byte[] b=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);writeVarInt(b.length);out.writeBytes(b);}
              public String readString(){int length=readVarInt();if(cursor+length>input.length)throw new IndexOutOfBoundsException();String v=new String(input,cursor,length,java.nio.charset.StandardCharsets.UTF_8);cursor+=length;return v;}
              public void padding(int count){for(int i=0;i<count;i++)out.write(0);}
            }
            """);
        sources.put(RECIPE, """
            package com.nik123123555.ptsdeco.crafting;
            public final class WorkbenchContructingRecipe {
              public final net.minecraft.class_2371 materials; public final net.minecraft.class_1799 result;
              public WorkbenchContructingRecipe(net.minecraft.class_2371 materials,net.minecraft.class_1799 result){this.materials=materials;this.result=result;}
            }
            """);
        sources.put("com.nik123123555.ptsdeco.crafting.StackedIngredient", """
            package com.nik123123555.ptsdeco.crafting;
            public final class StackedIngredient {
              public static final StackedIngredient EMPTY=new StackedIngredient(0); private final int count;
              public StackedIngredient(int count){this.count=count;}
              public static StackedIngredient fromNetwork(net.minecraft.class_2540 buf){return new StackedIngredient(buf.readInt());}
              public void toNetwork(net.minecraft.class_2540 buf){buf.method_53002(count);}
            }
            """);
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, source) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored){return source;}
        }));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            if (!compiler.getTask(null, manager, null, List.of("--release", "17", "-proc:none", "-d", classes.toString()), null, units).call()) throw new AssertionError("Stub compilation failed");
        }
    }
}
