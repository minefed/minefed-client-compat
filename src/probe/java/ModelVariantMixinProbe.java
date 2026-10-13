/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;

/** Applies both local captures to the pinned game's actual intermediary ModelLoader bytecode. */
public final class ModelVariantMixinProbe {
    private static final String TARGET = "net/minecraft/class_1088";

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        try (JarFile jar = new JarFile(args[0])) {
            byte[] before = read(jar, TARGET);
            ClassNode original = node(before);
            check(method(original, "method_4725").desc.equals("(Lnet/minecraft/class_2689;Ljava/lang/String;)Ljava/util/function/Predicate;"), "1.20.4 parser signature");
            check(calls(method(original, "method_4731"), "java/util/stream/Stream", "filter") == 1, "one original variant filter");
            check(calls(method(original, "method_4731"), "com/google/common/collect/ImmutableList", "stream") == 1,
                "original source is the captured immutable state list stream");
            MixinCompatProbe.ProbeService.officialClasses = new AbstractMap<>() {
                @Override public byte[] get(Object key) {
                    try { return read(jar, String.valueOf(key)); }
                    catch (IOException exception) { throw new UncheckedIOException(exception); }
                }
                @Override public Set<Entry<String, byte[]>> entrySet() { return Set.of(); }
            };
            MixinCompatProbe.ProbeService.fixtureClasses = output;
            System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
            Mixins.addConfiguration("minefed-model-variant-perf.mixins.json");
            var service = (MixinCompatProbe.ProbeService) MixinService.getService();
            var transformer = service.factory().createTransformer();
            MixinExtrasBootstrap.init();
            byte[] after = transformer.transformClassBytes(TARGET.replace('/', '.'), TARGET.replace('/', '.'), before);
            check(!Arrays.equals(before, after), "Mixin applied");
            ClassNode patched = node(after);
            MethodNode parser = method(patched, "method_4725"), filter = method(patched, "method_4731");
            check(callsContaining(parser, "minefed$preserveParsedConditions") == 1, "parsed Map captured at the original return");
            check(callsContaining(filter, "minefed$filterMatchingStates") == 1, "actual ImmutableList captured at filter");
            check(calls(filter, "java/util/stream/Stream", "filter") == 0, "only intended filter redirected");
            check(calls(filter, TARGET, "method_4725") == 1, "vanilla parser still called");
            check(calls(filter, "java/util/stream/Stream", "forEach") == 1, "vanilla consumer still called");
            check(instructionIndex(filter, "method_4725") < instructionIndexContaining(filter, "minefed$filterMatchingStates"), "parse and errors precede acceleration");
            for (String untouched : List.of("method_4739", "method_4722", "method_4730", "method_4724")) {
                Files.writeString(output.resolve(untouched + "-original.txt"), text(method(original, untouched)));
                Files.writeString(output.resolve(untouched + "-indexed.txt"), text(method(patched, untouched)));
                check(instructions(method(original, untouched)).equals(instructions(method(patched, untouched))), "unchanged vanilla predicate, overlap and property parsing: " + untouched);
            }
            Files.write(output.resolve("ModelLoader-original.class"), before);
            Files.write(output.resolve("ModelLoader-indexed.class"), after);
        }
        System.out.println("PASS: both shipped variant-index injections apply to actual 1.20.4 bytecode; parsing precedes filter, original predicate/property parsing/overlap consumer and forEach remain intact.");
    }

    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        if (entry == null) return null;
        try (var stream = jar.getInputStream(entry)) { return stream.readAllBytes(); }
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static long calls(MethodNode method, String owner, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)).count();
    }
    private static long callsContaining(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call && call.name.contains(name)).count();
    }
    private static int instructionIndex(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals(name)) return i;
        return -1;
    }
    private static int instructionIndexContaining(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.contains(name)) return i;
        return -1;
    }
    private static List<String> instructions(MethodNode method) {
        // A reader may expand compressed verifier frames, and Mixin rewrites debug labels.
        // Canonical labels denote executable instruction offsets, including the end offset.
        // Opcodes and every operand, member/descriptor/bootstrap reference, branch/switch
        // destination, and ordered exception range/type remain part of the comparison.
        List<LabelNode> offsets = new ArrayList<>();
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        offsets.add(new LabelNode());
        int offset = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) labels.put(label, offsets.get(offset));
            if (instruction.getOpcode() >= 0) { offset++; offsets.add(new LabelNode()); }
        }
        MethodNode normalized = new MethodNode(method.access, method.name, method.desc, method.signature,
            method.exceptions.toArray(String[]::new));
        offset = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) {
            normalized.instructions.add(offsets.get(offset++));
            normalized.instructions.add(instruction.clone(labels));
        }
        normalized.instructions.add(offsets.get(offset));
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            TryCatchBlockNode copied = new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type);
            copied.visibleTypeAnnotations = block.visibleTypeAnnotations;
            copied.invisibleTypeAnnotations = block.invisibleTypeAnnotations;
            normalized.tryCatchBlocks.add(copied);
        }
        return text(normalized).lines().toList();
    }
    private static String text(MethodNode method) {
        var text = new org.objectweb.asm.util.Textifier();
        method.accept(new org.objectweb.asm.util.TraceMethodVisitor(text));
        java.io.StringWriter output = new java.io.StringWriter();
        text.print(new java.io.PrintWriter(output));
        return output.toString();
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
