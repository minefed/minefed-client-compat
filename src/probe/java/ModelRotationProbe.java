/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.joml.Vector3f;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/** Differential execution of pinned original and Mixin-transformed Minecraft 1.20.4 rotation bytecode. */
public final class ModelRotationProbe {
    private static final String TARGET = "net/minecraft/class_796", ROTATION = "net/minecraft/class_789";
    private static final String CONFIG = "minefed-model-rotation.mixins.json";
    private static final String HELPER = "team/minefed/mods/clientcompat/model/ModelRotationCache";
    private static final AtomicInteger NAN_PAYLOAD_VARIATIONS = new AtomicInteger();
    private static final boolean REQUIRE_NAN_PAYLOAD = Boolean.getBoolean("rotation.requireNaNPayload");
    private static final Map<String, String> HASHES = Map.of(
        TARGET, "529dc9556b74681e90f3e1313a3e1904548c43d38396cd1e5aae1de776dc9bd4",
        TARGET + "$1", "03aab148dc1ccae63524f717f9fb4ccb1f0982a108792185991ef4632ad6e82c",
        ROTATION, "4e5886050b219a2c1bc7e5c92b0507bbeb7bc7d31750434e5d6ee044fa66c9cd");
    // ModelFixtureEquivalenceProbe compares all instructions/members/handlers
    // against the original Loom pins before this second fixture is accepted.
    private static final Map<String, String> CLIENT_HASHES = Map.of(
        TARGET, "c82e37effad2be5045b4a07e9c588ceec7c0711e5306aba42cabb26fb944933a",
        TARGET + "$1", "35dd964af85d677f9b3e50e5ee123bbeb2de589c6563cb42960049078e503022",
        ROTATION, "654f2d7169112652095145ae752d84398cc2e5a9b41945ff9f9976e58b7b2cdf");
    private static final Map<String, String> JOML_HASHES = Map.of(
        "Vector3f", "48c96ce5de5540832a230d33d8e8e1cd6c09e0678ac83a3d7a93d0d685b7953d",
        "Vector4f", "4989ea14dc925bcdfd560afad65fe8a3efa7162d119977df565ca224f539fb4b",
        "Matrix4f", "1eaa5621369741c338ecd98795dc849c2d86c94bcd537b34fa32ebd5c7c01f19",
        "Quaternionf", "36995083b5f4147f2af89c04e8395031b2a8de2a944bbc767cdab53c15dfa325",
        "Math", "0b6c59549e63236d53466134e24165481cfb47ed3096251b10467e2adf1fa23e",
        "Options", "a9dba25a37bbb4943d6673ef132814ba3cb0f10c2802cb816824367732b2303d");
    private static final float[] STANDARD = {0.0F, -0.0F, 22.5F, -22.5F, 45.0F, -45.0F};
    private static final float[] CUSTOM = {Float.NaN, Float.intBitsToFloat(0x7fc00123), Float.intBitsToFloat(0xffc00456),
        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE, -Float.MIN_VALUE,
        1.0F, 90.0F, 180.0F, -180.0F, Math.nextUp(22.5F), Math.nextDown(-45.0F), Float.MAX_VALUE};
    private static final float[] BOUNDARY = {0.0F, -0.0F, 1.0F, -1.0F, 0.5F, -0.5F,
        Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7fc00123)};

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ModelRotationProbe <Minecraft intermediary JAR> <output>");
        Path output = Path.of(args[1]);
        for (var entry : JOML_HASHES.entrySet()) if (!entry.getValue().equals(sha256(resource("org/joml/" + entry.getKey() + ".class"))))
            throw new AssertionError("Expected Minecraft's JOML 1.10.5 bytecode: " + entry.getKey());
        Map<String, byte[]> original = new HashMap<>();
        Map<String, String> actualHashes = new HashMap<>();
        try (ZipFile jar = new ZipFile(args[0])) {
            for (var entry : HASHES.entrySet()) {
                byte[] bytes = jar.getInputStream(jar.getEntry(entry.getKey() + ".class")).readAllBytes();
                actualHashes.put(entry.getKey(), sha256(bytes));
                original.put(entry.getKey(), bytes);
            }
        }
        String minecraftFixture = identifyFixture(actualHashes);
        checkFixtureSelection();
        System.out.println("Pinned Minecraft fixture: " + minecraftFixture);
        var config = PfmPerformanceProbe.json(CONFIG);
        if (!config.get("required").getAsBoolean() || config.getAsJsonArray("client").size() != 1
            || !"ModelRotationMixin".equals(config.getAsJsonArray("client").get(0).getAsString()))
            throw new AssertionError("Rotation Mixin must be required and client-only");
        boolean registered = false;
        for (var entry : PfmPerformanceProbe.json("fabric.mod.json").getAsJsonArray("mixins")) {
            if (entry.isJsonObject() && CONFIG.equals(entry.getAsJsonObject().get("config").getAsString())) {
                registered = "client".equals(entry.getAsJsonObject().get("environment").getAsString());
            }
        }
        if (!registered) throw new AssertionError("Rotation config must be registered for the client");
        checkReferences(original);
        checkOriginalCallSite(original.get(TARGET));
        Path fixtures = output.resolve("fixtures");
        compileFixtures(fixtures);
        MixinCompatProbe.ProbeService.officialClasses = original;
        MixinCompatProbe.ProbeService.fixtureClasses = fixtures;
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(CONFIG);
        IMixinTransformer transformer = ((MixinCompatProbe.ProbeService) MixinService.getService()).factory().createTransformer();
        byte[] transformed = transformer.transformClassBytes(TARGET.replace('/', '.'), TARGET.replace('/', '.'), original.get(TARGET));
        if (Arrays.equals(transformed, original.get(TARGET))) throw new AssertionError("Mixin was not applied");
        checkTransformed(original.get(TARGET), transformed);
        Path beforeDir = output.resolve("original"), afterDir = output.resolve("transformed");
        prepare(beforeDir, fixtures, original, original.get(TARGET));
        prepare(afterDir, fixtures, original, transformed);
        Files.write(output.resolve("full-transformed-quad-factory.class"), transformed);
        int cases = 0;
        boolean baselineOnly = Boolean.getBoolean("rotation.baselineOnly");
        if (baselineOnly) System.out.println("Diagnostic comparison: original bytecode against a second original class loader");
        try (Execution before = new Execution(beforeDir); Execution after = new Execution(baselineOnly ? beforeDir : afterDir)) {
            Random random = new Random(0x726f74617465L);
            for (int axis = 0; axis < 3; axis++) for (float angle : STANDARD) for (boolean rescale : new boolean[]{false, true}) {
                for (int trial = 0; trial < 300; trial++) {
                    float[] vertex = vector(random, trial), origin = vector(random, trial + 3);
                    compare(before, after, vertex, origin, axis, angle, rescale);
                    cases++;
                }
            }
            for (int axis = 0; axis < 3; axis++) for (float angle : CUSTOM) for (boolean rescale : new boolean[]{false, true}) {
                if (after.cached(axis, angle, rescale)) throw new AssertionError("Nonstandard angle was cached");
                for (int trial = 0; trial < 30; trial++) {
                    compare(before, after, vector(random, trial), vector(random, trial + 3), axis, angle, rescale);
                    cases++;
                }
            }
            // The input vectors are mutable; reusing the same rotation record must observe its current origin.
            before.checkMutableOrigin(after);
            // A second transform hook may mutate matrix/scale arguments; cached data must never escape.
            before.setMutation(true); after.setMutation(true);
            for (int repeat = 0; repeat < 3; repeat++) for (int axis = 0; axis < 3; axis++) for (float angle : STANDARD) {
                compare(before, after, new float[]{0.25F, -0.0F, 0.75F}, new float[]{0.5F, 0.5F, 0.5F}, axis, angle, true);
                cases++;
            }
            before.setMutation(false); after.setMutation(false);
            for (int axis = 0; axis < 3; axis++) for (float angle : STANDARD) {
                compare(before, after, new float[]{0.25F, -0.0F, 0.75F}, new float[]{0.5F, 0.5F, 0.5F}, axis, angle, true);
                cases++;
            }
            before.checkNulls(after);
            int hookCalls = after.hookCalls();
            if (hookCalls == 0 || hookCalls != before.hookCalls()) throw new AssertionError("Original transform hook was bypassed");
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                List<Future<?>> jobs = new ArrayList<>();
                for (int worker = 0; worker < 6; worker++) {
                    final int seed = worker;
                    jobs.add(pool.submit(() -> {
                        Random rng = new Random(seed);
                        try {
                            for (int trial = 0; trial < 1000; trial++) compare(before, after,
                                vector(rng, trial), vector(rng, trial + 3), trial % 3, STANDARD[trial % STANDARD.length], trial % 2 == 0);
                        } catch (Exception exception) { throw new CompletionException(exception); }
                    }));
                }
                for (Future<?> job : jobs) job.get();
            } finally { pool.shutdownNow(); }
            cases += 6000;
        }
        String summary = "{\"rawFloatBitCases\":" + cases + ",\"standardRotations\":36,\"parallelWorkers\":6,"
            + "\"pinnedVanillaBytecode\":true,\"minecraftFixture\":\"" + minecraftFixture + "\",\"pinnedJoml105\":true,\"realMixinTransformation\":true,\"mutableOrigin\":true,"
            + "\"mutatingTransformHook\":true,\"nonstandardFallback\":true,\"originalFallbackBytecodeUnchanged\":true,"
            + "\"finiteResultsRawBitExact\":true,\"observedOriginalFallbackNaNPayloadVariations\":" + NAN_PAYLOAD_VARIATIONS.get()
            + ",\"strictNaNPayloadComparison\":" + REQUIRE_NAN_PAYLOAD + "}";
        Files.writeString(output.resolve("summary.json"), summary);
        System.out.println("PASS: " + cases + " original/transformed comparisons, finite results raw-bit exact, 36 standard rotations, proven original nonstandard/nonfinite fallback, mutable origins, transform-hook mutations and 6 concurrent workers.");
        System.out.println("NaN diagnostics: " + NAN_PAYLOAD_VARIATIONS.get() + " payload/sign differences on proven original-bytecode fallback calls; strict payload comparison=" + REQUIRE_NAN_PAYLOAD + ".");
        System.out.println("Scope: original Minecraft 1.20.4 rotation/transform methods and real Sponge Mixin; Axis is a signature fixture. No whole-client speed claim.");
    }

    private static String identifyFixture(Map<String, String> hashes) {
        if (HASHES.equals(hashes)) return "loom-merged-1.20.4";
        if (CLIENT_HASHES.equals(hashes)) return "official-client-1.20.4-tinyremapper-0.10.3";
        throw new AssertionError("Unexpected or mixed Minecraft fixture bytecode: " + hashes);
    }
    private static void checkFixtureSelection() {
        if (identifyFixture(HASHES).equals(identifyFixture(CLIENT_HASHES))) throw new AssertionError("Fixture identity lost");
        Map<String, String> mixed = new HashMap<>(HASHES);
        mixed.put(TARGET, CLIENT_HASHES.get(TARGET));
        try { identifyFixture(mixed); } catch (AssertionError expected) { return; }
        throw new AssertionError("Mixed fixture hashes must be rejected");
    }

    private static float[] vector(Random random, int trial) {
        float[] result = new float[3];
        for (int i = 0; i < result.length; i++) result[i] = trial < BOUNDARY.length ? BOUNDARY[(trial + i) % BOUNDARY.length]
            : trial % 2 == 0 ? Float.intBitsToFloat(random.nextInt()) : (random.nextFloat() - 0.5F) * 128;
        return result;
    }

    private static void compare(Execution before, Execution after, float[] vertex, float[] origin, int axis, float angle, boolean rescale) throws Exception {
        int originalCallsBefore = after.originalCalls();
        Vector3f original = before.run(vertex, origin, axis, angle, rescale);
        Vector3f transformed = after.run(vertex, origin, axis, angle, rescale);
        boolean optimized = after.redirected && after.cached(axis, angle, rescale) && after.supports(vertex, origin);
        boolean usedOriginal = after.originalCalls() - originalCallsBefore == 1;
        if (optimized == usedOriginal) throw new AssertionError("Wrong original/optimized call path");
        try {
            for (int component = 0; component < 3; component++) {
                float a = original.get(component), b = transformed.get(component);
                if (Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b)) continue;
                // NaN payload/sign propagation can differ between interpreter/C1/C2 even when
                // executing unchanged original instructions. Only accept that after proving
                // this invocation used those original instructions; -Xint strict mode also
                // checks the raw NaN payloads, while finite outputs always remain bit-exact.
                if (!REQUIRE_NAN_PAYLOAD && usedOriginal && Float.isNaN(a) && Float.isNaN(b)) {
                    NAN_PAYLOAD_VARIATIONS.incrementAndGet();
                } else {
                    throw new AssertionError("Raw float bits changed for component " + component);
                }
            }
        } catch (AssertionError mismatch) {
            System.err.println("Rotation case axis=" + axis + " angle=" + hex(angle) + " rescale=" + rescale
                + " vertex=" + hex(new Vector3f(vertex)) + " origin=" + hex(new Vector3f(origin))
                + " original=" + hex(original) + " transformed=" + hex(transformed));
            for (int repeat = 0; repeat < 4; repeat++) System.err.println("Repeat " + repeat + " original="
                + hex(before.run(vertex, origin, axis, angle, rescale)) + " transformed="
                + hex(after.run(vertex, origin, axis, angle, rescale)));
            throw mismatch;
        }
    }

    private static String hex(float value) { return String.format("%08x", Float.floatToRawIntBits(value)); }
    private static String hex(Vector3f value) { return hex(value.x) + "," + hex(value.y) + "," + hex(value.z); }

    private static void bitsEqual(Vector3f before, Vector3f after) {
        for (int axis = 0; axis < 3; axis++) if (Float.floatToRawIntBits(before.get(axis)) != Float.floatToRawIntBits(after.get(axis)))
            throw new AssertionError("Raw float bits changed for component " + axis + ": " + before + " / " + after);
    }

    private static void checkReferences(Map<String, byte[]> original) throws Exception {
        Set<String> known = new HashSet<>();
        for (byte[] bytes : original.values()) {
            ClassNode node = node(bytes);
            for (MethodNode method : node.methods) {
                known.add(node.name + "." + method.name + method.desc);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) known.add(call.owner + "." + call.name + call.desc);
                    if (instruction instanceof FieldInsnNode field) known.add(field.owner + "." + field.name + ":" + field.desc);
                }
            }
        }
        for (String name : List.of(HELPER, "team/minefed/mods/clientcompat/mixin/ModelRotationMixin")) {
            for (MethodNode method : node(resource(name + ".class")).methods) for (AbstractInsnNode instruction : method.instructions) {
                String reference = null;
                if (instruction instanceof MethodInsnNode call && call.owner.startsWith("net/minecraft/")) reference = call.owner + "." + call.name + call.desc;
                if (instruction instanceof FieldInsnNode field && field.owner.startsWith("net/minecraft/")) reference = field.owner + "." + field.name + ":" + field.desc;
                if (reference != null && !known.contains(reference)) throw new AssertionError("Stub differs from pinned bytecode: " + reference);
            }
        }
    }

    private static void checkTransformed(byte[] original, byte[] transformed) {
        ClassNode node = node(transformed);
        int handler = 0, originalTransform = 0, originalFallback = 0, remainingOriginalCalls = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (method.name.equals("method_3461") && call.name.contains("minefed$reuseStandardRotation")) handler++;
            if (method.name.equals("method_3461") && call.name.equals("method_3463")) remainingOriginalCalls++;
            if (method.name.contains("minefed$reuseStandardRotation") && call.name.equals("method_3464")) originalTransform++;
            if (method.name.contains("minefed$reuseStandardRotation") && call.name.equals("method_3463")) originalFallback++;
        }
        if (handler != 1 || originalTransform != 1 || originalFallback != 2 || remainingOriginalCalls != 0)
            throw new AssertionError("Unexpected call-site redirect/transform/fallback counts");
        if (!Arrays.equals(isolatedMethod(original, "method_3463"), isolatedMethod(transformed, "method_3463")))
            throw new AssertionError("Original fallback method instructions changed");
    }

    private static byte[] isolatedMethod(byte[] bytes, String name) {
        MethodNode method = node(bytes).methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
        // Compare executable instructions and branch/exception targets, not recalculated verifier
        // frames or Mixin's debug labels. Canonical labels point at executable instruction offsets.
        List<LabelNode> offsets = new ArrayList<>();
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        offsets.add(new LabelNode());
        int offset = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) labels.put(label, offsets.get(offset));
            if (instruction.getOpcode() >= 0) { offset++; offsets.add(new LabelNode()); }
        }
        MethodNode normalized = new MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new));
        offset = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) {
            normalized.instructions.add(offsets.get(offset++));
            normalized.instructions.add(instruction.clone(labels));
        }
        normalized.instructions.add(offsets.get(offset));
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            normalized.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
        }
        var text = new org.objectweb.asm.util.Textifier();
        normalized.accept(new org.objectweb.asm.util.TraceMethodVisitor(text));
        StringWriter output = new StringWriter(); text.print(new PrintWriter(output));
        return output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void checkOriginalCallSite(byte[] original) {
        int calls = 0;
        for (MethodNode method : node(original).methods) if (method.name.equals("method_3461")) {
            for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("method_3463")) {
                AbstractInsnNode previous = call;
                for (int local : new int[]{8, 11, 0}) {
                    do { previous = previous.getPrevious(); } while (previous != null && previous.getOpcode() < 0);
                    if (!(previous instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != local)
                        throw new AssertionError("Rotation call site no longer invokes this factory with its vertex and rotation");
                }
                calls++;
            }
        }
        if (calls != 1) throw new AssertionError("Expected one pinned rotation call site");
    }

    private static void compileFixtures(Path output) throws IOException {
        Path source = output.resolve("net/minecraft/class_2350.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package net.minecraft; public class class_2350 { public enum class_2351 {field_11048,field_11052,field_11051;} }");
        Path hooks = output.resolve("RotationProbeHooks.java");
        Files.writeString(hooks, "import org.joml.*; import java.util.concurrent.atomic.AtomicInteger; public final class RotationProbeHooks { "
            + "public static volatile boolean mutate; public static final AtomicInteger calls = new AtomicInteger();"
            + "public static final ThreadLocal<AtomicInteger> originalCalls=ThreadLocal.withInitial(AtomicInteger::new);"
            + "public static void enterOriginal(){originalCalls.get().incrementAndGet();}"
            + "public static void before(Matrix4f matrix,Vector3f scale){calls.incrementAndGet(); if(mutate){matrix.m00(0.375F);scale.x=-2.0F;}} }");
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-encoding", "UTF-8", "-proc:none",
            "-cp", System.getProperty("java.class.path"), "-d", output.toString(), source.toString(), hooks.toString());
        if (result != 0) throw new AssertionError("Fixture compilation failed: " + result);
    }

    private static void prepare(Path output, Path fixtures, Map<String, byte[]> originals, byte[] factory) throws Exception {
        for (var entry : originals.entrySet()) write(output, entry.getKey(), entry.getKey().equals(TARGET) ? isolate(factory) : entry.getValue());
        for (String helper : List.of(HELPER, HELPER + "$Rotation")) write(output, helper, resource(helper + ".class"));
        try (var files = Files.walk(fixtures)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                Path destination = output.resolve(fixtures.relativize(file));
                Files.createDirectories(destination.getParent()); Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Other model types are irrelevant to this kernel; retain the actual rotation and transform instructions. */
    private static byte[] isolate(byte[] bytes) {
        ClassNode node = node(bytes);
        node.methods.removeIf(method -> !Set.of("<init>", "<clinit>", "method_3463").contains(method.name)
            && !(method.name.equals("method_3464") && method.desc.contains("Lorg/joml/Matrix4f;")) && !method.name.contains("minefed"));
        for (MethodNode method : node.methods) if (method.name.equals("method_3464")) {
            InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 3)); hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "RotationProbeHooks", "before", "(Lorg/joml/Matrix4f;Lorg/joml/Vector3f;)V", false));
            method.instructions.insert(hook);
        }
        for (MethodNode method : node.methods) if (method.name.equals("method_3463")) {
            method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "RotationProbeHooks", "enterOriginal", "()V", false));
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }

    private static final class Execution implements AutoCloseable {
        final URLClassLoader loader;
        final Object factory;
        final Method rotate, find, supports;
        final boolean redirected;
        final Constructor<?> rotation;
        final Object[] axes;
        final Class<?> hooks;
        Execution(Path root) throws Exception {
            loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, ModelRotationProbe.class.getClassLoader()) {
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("net.minecraft.") || name.startsWith(HELPER.replace('/', '.')) || name.equals("RotationProbeHooks")) {
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name); if (loaded == null) loaded = findClass(name);
                            if (resolve) resolveClass(loaded); return loaded;
                        }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            Class<?> target = loader.loadClass(TARGET.replace('/', '.')), record = loader.loadClass(ROTATION.replace('/', '.'));
            Class<?> axis = loader.loadClass("net.minecraft.class_2350$class_2351");
            axes = axis.getEnumConstants();
            factory = target.getConstructor().newInstance();
            Method redirect = Arrays.stream(target.getDeclaredMethods()).filter(method -> method.getName().contains("minefed$reuseStandardRotation")).findFirst().orElse(null);
            redirected = redirect != null;
            rotate = redirected ? redirect : target.getDeclaredMethod("method_3463", Vector3f.class, record); rotate.setAccessible(true);
            rotation = record.getConstructor(Vector3f.class, axis, float.class, boolean.class);
            find = loader.loadClass(HELPER.replace('/', '.')).getMethod("find", axis, float.class, boolean.class);
            supports = loader.loadClass(HELPER.replace('/', '.')).getMethod("supports", Vector3f.class, Vector3f.class);
            hooks = loader.loadClass("RotationProbeHooks");
        }
        Vector3f run(float[] vertex, float[] origin, int axis, float angle, boolean rescale) throws Exception {
            Vector3f value = new Vector3f(vertex); invoke(value, rotation.newInstance(new Vector3f(origin), axes[axis], angle, rescale)); return value;
        }
        void invoke(Vector3f vertex, Object modelRotation) throws Exception {
            if (redirected) rotate.invoke(factory, factory, vertex, modelRotation);
            else rotate.invoke(factory, vertex, modelRotation);
        }
        boolean cached(int axis, float angle, boolean rescale) throws Exception { return find.invoke(null, axes[axis], angle, rescale) != null; }
        boolean supports(float[] vertex, float[] origin) throws Exception { return (boolean) supports.invoke(null, new Vector3f(vertex), new Vector3f(origin)); }
        int originalCalls() throws Exception { return ((AtomicInteger) ((ThreadLocal<?>) hooks.getField("originalCalls").get(null)).get()).get(); }
        void setMutation(boolean value) throws Exception { hooks.getField("mutate").setBoolean(null, value); }
        int hookCalls() throws Exception { return ((java.util.concurrent.atomic.AtomicInteger) hooks.getField("calls").get(null)).get(); }
        void checkMutableOrigin(Execution after) throws Exception {
            Vector3f firstOrigin = new Vector3f(0.5F), secondOrigin = new Vector3f(0.5F);
            Object first = rotation.newInstance(firstOrigin, axes[1], 22.5F, true);
            Object second = after.rotation.newInstance(secondOrigin, after.axes[1], 22.5F, true);
            Vector3f previous = null;
            for (int i = 0; i < 4; i++) {
                firstOrigin.set(i, -i, i * 0.25F); secondOrigin.set(firstOrigin);
                Vector3f left = new Vector3f(0.125F), right = new Vector3f(left);
                invoke(left, first); after.invoke(right, second); bitsEqual(left, right);
                if (previous != null && previous.equals(right)) throw new AssertionError("Mutable origin was not observed");
                previous = new Vector3f(right);
            }
        }
        void checkNulls(Execution after) throws Exception {
            Vector3f before = new Vector3f(1, -0.0F, 2), fixed = new Vector3f(before);
            invoke(before, null); after.invoke(fixed, null); bitsEqual(before, fixed);
            if (find.invoke(null, null, 0.0F, false) != null || after.find.invoke(null, null, 0.0F, false) != null) throw new AssertionError("Null axis must fall back");
            for (Execution execution : List.of(this, after)) {
                try { execution.invoke(new Vector3f(), execution.rotation.newInstance(new Vector3f(), null, 0.0F, false)); throw new AssertionError("Null axis unexpectedly succeeded"); }
                catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof NullPointerException)) throw expected; }
            }
        }
        public void close() throws IOException { loader.close(); }
    }

    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] resource(String name) throws IOException { try (InputStream input = ModelRotationProbe.class.getClassLoader().getResourceAsStream(name)) { if (input == null) throw new IOException("Missing " + name); return input.readAllBytes(); } }
    private static void write(Path root, String name, byte[] bytes) throws IOException { Path target = root.resolve(name + ".class"); Files.createDirectories(target.getParent()); Files.write(target, bytes); }
    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
