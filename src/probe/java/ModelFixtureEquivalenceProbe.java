/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;

/** One-time reproducible audit of the old Loom and independently prepared client fixtures. */
public final class ModelFixtureEquivalenceProbe {
    static final String LOOM = "loom-merged-1.20.4";
    static final String CLIENT = "official-client-1.20.4-tinyremapper-0.10.3";
    static final Map<String, Map<String, String>> HASHES = Map.of(
        LOOM, Map.of(
            "net/minecraft/class_1088", "057c68fc6c872bb8068a9d2c495712fd9c861929adc356223ea6d5743a2a9bb5",
            "net/minecraft/class_796", "529dc9556b74681e90f3e1313a3e1904548c43d38396cd1e5aae1de776dc9bd4",
            "net/minecraft/class_796$1", "03aab148dc1ccae63524f717f9fb4ccb1f0982a108792185991ef4632ad6e82c",
            "net/minecraft/class_789", "4e5886050b219a2c1bc7e5c92b0507bbeb7bc7d31750434e5d6ee044fa66c9cd"),
        CLIENT, Map.of(
            "net/minecraft/class_1088", "a3d305ef024c1699995098e8fefe9e40efa04e6ff92c5c52dcc92ecae286bf55",
            "net/minecraft/class_796", "c82e37effad2be5045b4a07e9c588ceec7c0711e5306aba42cabb26fb944933a",
            "net/minecraft/class_796$1", "35dd964af85d677f9b3e50e5ee123bbeb2de589c6563cb42960049078e503022",
            "net/minecraft/class_789", "654f2d7169112652095145ae752d84398cc2e5a9b41945ff9f9976e58b7b2cdf"));

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: ModelFixtureEquivalenceProbe <pinned Loom JAR> <pinned client JAR> <output>");
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        int methods = 0, fields = 0, instructions = 0, handlers = 0;
        List<String> failures = new ArrayList<>();
        try (ZipFile loom = new ZipFile(args[0]); ZipFile client = new ZipFile(args[1])) {
            for (String name : HASHES.get(LOOM).keySet().stream().sorted().toList()) {
                byte[] before = read(loom, name), after = read(client, name);
                check(HASHES.get(LOOM).get(name).equals(sha256(before)), "pinned Loom class " + name);
                check(HASHES.get(CLIENT).get(name).equals(sha256(after)), "pinned client class " + name);
                if (name.equals("net/minecraft/class_1088")) negativeControls(before);
                ClassNode original = node(before, 0), prepared = node(after, 0);
                String file = name.substring(name.lastIndexOf('/') + 1);
                Files.writeString(output.resolve(file + "-loom-raw.txt"), text(original));
                Files.writeString(output.resolve(file + "-client-raw.txt"), text(prepared));
                String a = normalized(before), b = normalized(after);
                Files.writeString(output.resolve(file + "-loom-normalized.txt"), a);
                Files.writeString(output.resolve(file + "-client-normalized.txt"), b);
                if (!a.equals(b)) failures.add(name);
                methods += original.methods.size();
                fields += original.fields.size();
                for (MethodNode method : original.methods) {
                    handlers += method.tryCatchBlocks.size();
                    for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) instructions++;
                }
            }
        }
        check(failures.isEmpty(), "fixture instruction/class differences: " + failures + "; inspect normalized dumps");
        String summary = "{\"classes\":4,\"methods\":" + methods + ",\"fields\":" + fields
            + ",\"instructions\":" + instructions + ",\"exceptionHandlers\":" + handlers
            + ",\"allMembersAndInstructionsEqual\":true,\"controlFlowAndExceptionRangesEqual\":true,"
            + "\"negativeControls\":6,\"nullableDescriptorSubstitutions\":5,"
            + "\"normalization\":[\"source/line/local-variable debug metadata and parameter names (not flags)\",\"verifier frame encoding\",\"instruction-offset labels\",\"Fabric CLIENT environment annotation\",\"empty javax/JetBrains Nullable annotation descriptor\"]}";
        Files.writeString(output.resolve("summary.json"), summary);
        System.out.println("PASS: " + summary);
    }

    private static byte[] read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name + ".class");
        if (entry == null) throw new AssertionError("Missing pinned class: " + name);
        try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }
    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static ClassNode node(byte[] bytes, int flags) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, flags); return node;
    }
    private static String text(ClassNode node) {
        StringWriter result = new StringWriter();
        node.accept(new TraceClassVisitor(new PrintWriter(result)));
        return result.toString();
    }

    private static String normalized(byte[] bytes) {
        // Preserve MethodParameters count/flags and all type annotations; only
        // parameter names and source/line/local-variable debug tables differ.
        // SKIP_FRAMES ignores verifier serialization, not instructions/handlers.
        ClassNode node = node(bytes, ClassReader.SKIP_FRAMES);
        node.sourceFile = null; node.sourceDebug = null;
        node.invisibleAnnotations = withoutClientEnvironment(node.invisibleAnnotations);
        node.visibleAnnotations = withoutClientEnvironment(node.visibleAnnotations);
        int nullableAliases = 0;
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = node.methods.get(i);
            method.localVariables = null;
            if (method.parameters != null) for (ParameterNode parameter : method.parameters) parameter.name = null;
            nullableAliases += normalizeNullability(method.visibleAnnotations);
            nullableAliases += normalizeNullability(method.invisibleAnnotations);
            if (method.visibleParameterAnnotations != null) for (var annotations : method.visibleParameterAnnotations)
                nullableAliases += normalizeNullability(annotations);
            if (method.invisibleParameterAnnotations != null) for (var annotations : method.invisibleParameterAnnotations)
                nullableAliases += normalizeNullability(annotations);
            node.methods.set(i, normalized(method));
        }
        // Exactly the observed metadata aliases are allowed. Other annotations,
        // their values, visibility, positions and all type annotations survive.
        check(nullableAliases == 0 || node.name.equals("net/minecraft/class_1088") && nullableAliases == 1
            || node.name.equals("net/minecraft/class_796") && nullableAliases == 4, "reviewed Nullable alias count");
        return text(node);
    }
    private static int normalizeNullability(List<AnnotationNode> annotations) {
        int count = 0;
        if (annotations != null) for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Ljavax/annotation/Nullable;")) continue;
            check(annotation.values == null || annotation.values.isEmpty(), "only the exact empty Nullable alias may differ");
            annotation.desc = "Lorg/jetbrains/annotations/Nullable;";
            count++;
        }
        return count;
    }
    private static List<AnnotationNode> withoutClientEnvironment(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        List<AnnotationNode> result = new ArrayList<>();
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lnet/fabricmc/api/Environment;")) { result.add(annotation); continue; }
            check(annotation.values.size() == 2 && annotation.values.get(0).equals("value")
                && annotation.values.get(1) instanceof String[] value && value.length == 2
                && value[0].equals("Lnet/fabricmc/api/EnvType;") && value[1].equals("CLIENT"), "only the exact Fabric CLIENT marker may differ");
        }
        return result;
    }
    private static MethodNode normalized(MethodNode method) {
        List<LabelNode> offsets = new ArrayList<>();
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        offsets.add(new LabelNode());
        int offset = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) labels.put(label, offsets.get(offset));
            if (instruction.getOpcode() >= 0) { offset++; offsets.add(new LabelNode()); }
        }
        InsnList normalized = new InsnList();
        offset = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) {
            normalized.add(offsets.get(offset++));
            normalized.add(instruction.clone(labels));
        }
        normalized.add(offsets.get(offset));
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            block.start = labels.get(block.start); block.end = labels.get(block.end); block.handler = labels.get(block.handler);
        }
        normalizeLocalAnnotationLabels(method.visibleLocalVariableAnnotations, labels);
        normalizeLocalAnnotationLabels(method.invisibleLocalVariableAnnotations, labels);
        method.instructions = normalized;
        return method;
    }
    private static void normalizeLocalAnnotationLabels(List<LocalVariableAnnotationNode> annotations, Map<LabelNode, LabelNode> labels) {
        if (annotations != null) for (LocalVariableAnnotationNode annotation : annotations) {
            annotation.start = annotation.start.stream().map(labels::get).toList();
            annotation.end = annotation.end.stream().map(labels::get).toList();
        }
    }
    private static void negativeControls(byte[] bytes) {
        assertDifferent(bytes, node -> node.fields.get(0).name += "$changed", "field metadata");
        assertDifferent(bytes, node -> node.methods.get(0).access ^= Opcodes.ACC_SYNCHRONIZED, "method metadata");
        assertDifferent(bytes, node -> node.methods.get(0).parameters.get(0).access ^= Opcodes.ACC_FINAL, "parameter flags");
        assertDifferent(bytes, node -> node.methods.get(0).instructions.insert(new InsnNode(Opcodes.NOP)), "executable opcode");
        assertDifferent(bytes, node -> {
            MethodNode method = node.methods.get(0);
            for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof JumpInsnNode jump) {
                LabelNode end = new LabelNode(); method.instructions.add(end); jump.label = end; return;
            }
            throw new AssertionError("negative control requires an original branch");
        }, "branch destination");
        assertDifferent(bytes, node -> node.methods.get(0).tryCatchBlocks.get(0).type = "java/lang/Error", "ordered exception handler type");
    }
    private static void assertDifferent(byte[] bytes, Consumer<ClassNode> mutation, String label) {
        ClassNode changed = node(bytes, ClassReader.SKIP_FRAMES);
        mutation.accept(changed);
        ClassWriter writer = new ClassWriter(0); changed.accept(writer);
        check(!normalized(bytes).equals(normalized(writer.toByteArray())), "normalizer must retain " + label);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
