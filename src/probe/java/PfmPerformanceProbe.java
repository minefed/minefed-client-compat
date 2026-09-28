/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import com.google.gson.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/**
 * Applies the shipped PFM performance Mixins to the official PFM 1.5.0 classes and
 * compares original and transformed tick code on small Minecraft/PFM fixtures.
 */
public final class PfmPerformanceProbe {
    private static final String SHA256 = "35c0dfdfa11f022b340f429f509f3bbd238134cf4224c8f11ab6e9c8f2e04fbf";
    private static final String BE = "com/unlikepaladin/pfm/blocks/blockentities/", RENDER = "com/unlikepaladin/pfm/entity/render/";
    private static final String STOVE = BE + "StoveBlockEntity", STOVETOP = BE + "StovetopBlockEntity", MICROWAVE = BE + "MicrowaveBlockEntity";
    private static final String PLATE_RENDERER = RENDER + "PlateBlockEntityRenderer", TRASHCAN_RENDERER = RENDER + "TrashcanBlockEntityRenderer",
        MICROWAVE_RENDERER = RENDER + "MicrowaveBlockEntityRenderer";
    private static final String MIXINS = "team/minefed/mods/clientcompat/mixin/";
    private static final List<String> COMMON_MIXINS = List.of("PfmCookingUpdateMixin", "PfmMicrowaveTickMixin");
    private static final List<String> CLIENT_MIXINS = List.of("PfmMicrowaveRendererMixin", "PfmPlateRendererMixin", "PfmTrashcanRendererMixin");
    /** Minecraft classes the dedicated-server Mixins may name; all are common (non-client) classes. */
    private static final Set<String> COMMON_MINECRAFT = Set.of("class_1263", "class_1863", "class_1937", "class_2338", "class_2371",
        "class_2680", "class_3956");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PfmPerformanceProbe <official PFM JAR> <output>");
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if (!SHA256.equals(hash)) throw new IllegalArgumentException("Expected the official PFM 1.5.0 Fabric 1.20.4 artifact");
        Map<String, byte[]> pfm = new HashMap<>();
        try (JarFile zip = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(zip.entries()))
                if (entry.getName().endsWith(".class"))
                    pfm.put(entry.getName().substring(0, entry.getName().length() - 6), zip.getInputStream(entry).readAllBytes());
        }
        checkConfigurations();
        checkReferences(pfm);

        if (Files.exists(output)) try (var files = Files.walk(output)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Path fixtures = output.resolve("fixtures");
        compileFixtures(fixtures);
        MixinCompatProbe.ProbeService.officialClasses = pfm;
        MixinCompatProbe.ProbeService.fixtureClasses = fixtures;
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("minefed-pfm-perf.mixins.json");
        Mixins.addConfiguration("minefed-pfm-render-perf.mixins.json");
        var service = (MixinCompatProbe.ProbeService) MixinService.getService();
        IMixinTransformer transformer = service.factory().createTransformer();
        MixinExtrasBootstrap.init();
        Map<String, byte[]> fixed = new HashMap<>();
        for (String target : List.of(STOVE, STOVETOP, MICROWAVE, PLATE_RENDERER, TRASHCAN_RENDERER, MICROWAVE_RENDERER)) {
            String name = target.replace('/', '.');
            byte[] result = transformer.transformClassBytes(name, name, pfm.get(target));
            if (Arrays.equals(result, pfm.get(target))) throw new AssertionError("Mixin was not applied to " + name);
            fixed.put(target, result);
        }
        expectHandlerCalls(fixed.get(STOVE), "litServerTick", 3);
        expectHandlerCalls(fixed.get(STOVETOP), "litServerTick", 3);
        expectHandlerCalls(fixed.get(MICROWAVE), "tick", 1);
        expectHandlerCalls(fixed.get(PLATE_RENDERER), "render", 1);
        expectHandlerCalls(fixed.get(TRASHCAN_RENDERER), "render", 2);
        expectHandlerCalls(fixed.get(MICROWAVE_RENDERER), "render", 4);

        for (String stove : List.of(STOVE, STOVETOP)) checkCooking(output, fixtures, stove, pfm.get(stove), fixed.get(stove));
        checkMicrowaveTick(output, fixtures, pfm.get(MICROWAVE), fixed.get(MICROWAVE));
        checkRenderers(output, fixtures, pfm, fixed);
        System.out.println("PASS: shipped PFM performance Mixins apply to official PFM 1.5.0 bytecode; stove/stovetop ticks store and send "
            + "exactly as before except unchanged finished items, idle microwaves skip only their unused lookup, and empty "
            + "plate/trash can/microwave renderers stop only where PFM would draw nothing.");
        System.out.println("Scope: real PFM tick/render bytecode and Sponge/MixinExtras transformation; Minecraft classes are fixtures. "
            + "Packet volume and frame time must be measured in game.");
    }

    /** Configurations loaded on dedicated servers; TrafficCraftPerformanceProbe checks the TrafficCraft one. */
    private static final Set<String> SERVER_CONFIGS = Set.of("minefed-pfm-perf.mixins.json", "minefed-trafficcraft-perf.mixins.json");

    /** fabric.mod.json must load the common config on both sides and keep render Mixins client-only. */
    private static void checkConfigurations() throws IOException {
        JsonObject mod = json("fabric.mod.json");
        if (!"*".equals(mod.get("environment").getAsString())) throw new AssertionError("The mod must load on dedicated servers");
        Map<String, String> environments = new HashMap<>();
        for (JsonElement entry : mod.getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive()) environments.put(entry.getAsString(), "*");
            else environments.put(entry.getAsJsonObject().get("config").getAsString(), entry.getAsJsonObject().get("environment").getAsString());
        }
        for (var entry : environments.entrySet())
            if (!SERVER_CONFIGS.contains(entry.getKey()) && !entry.getValue().equals("client"))
                throw new AssertionError(entry.getKey() + " must stay client-only");
        if (!"*".equals(environments.get("minefed-pfm-perf.mixins.json"))) throw new AssertionError("Server Mixin config is not loaded on servers");
        expectConfig("minefed-pfm-perf.mixins.json", "mixins", COMMON_MIXINS);
        expectConfig("minefed-pfm-render-perf.mixins.json", "client", CLIENT_MIXINS);
    }

    private static void expectConfig(String name, String section, List<String> classes) throws IOException {
        JsonObject config = json(name);
        List<String> listed = new ArrayList<>();
        for (JsonElement element : config.getAsJsonArray(section)) listed.add(element.getAsString());
        for (String other : List.of("mixins", "client", "server"))
            if (!other.equals(section) && config.has(other)) throw new AssertionError(name + " must not list " + other + " Mixins");
        if (!listed.equals(classes) || !config.get("required").getAsBoolean()) throw new AssertionError("Unexpected " + name);
    }

    static JsonObject json(String resource) throws IOException {
        try (InputStream stream = PfmPerformanceProbe.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new FileNotFoundException(resource);
            return new JsonParser().parse(new String(stream.readAllBytes(), "UTF-8")).getAsJsonObject();
        }
    }

    /**
     * The Mixins compile against small signature stubs. Every Minecraft/PFM member they call must be one
     * the official PFM bytecode itself references or declares, with the same owner and descriptor.
     */
    private static void checkReferences(Map<String, byte[]> pfm) throws IOException {
        Set<String> known = new HashSet<>();
        for (byte[] bytes : pfm.values()) {
            ClassNode node = node(bytes);
            for (FieldNode field : node.fields) known.add(node.name + "." + field.name + ":" + field.desc);
            for (MethodNode method : node.methods) {
                known.add(node.name + "." + method.name + method.desc);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) known.add(call.owner + "." + call.name + call.desc);
                    if (insn instanceof FieldInsnNode field) known.add(field.owner + "." + field.name + ":" + field.desc);
                }
            }
        }
        int checked = 0;
        for (String mixin : concat(COMMON_MIXINS, CLIENT_MIXINS)) {
            byte[] bytes;
            try (InputStream stream = PfmPerformanceProbe.class.getClassLoader().getResourceAsStream(MIXINS + mixin + ".class")) {
                bytes = stream.readAllBytes();
            }
            ClassNode node = node(bytes);
            for (MethodNode method : node.methods) {
                for (Type type : Type.getArgumentTypes(method.desc)) checkServerSafe(mixin, type.getInternalName());
                for (AbstractInsnNode insn : method.instructions) {
                    String owner = null, member = null;
                    if (insn instanceof MethodInsnNode call) { owner = call.owner; member = call.owner + "." + call.name + call.desc; }
                    if (insn instanceof FieldInsnNode field && !field.owner.equals(node.name)) {
                        owner = field.owner; member = field.owner + "." + field.name + ":" + field.desc;
                    }
                    if (insn instanceof TypeInsnNode type) checkServerSafe(mixin, type.desc);
                    if (owner == null || !(owner.startsWith("net/minecraft/") || owner.startsWith("com/unlikepaladin/"))) continue;
                    checkServerSafe(mixin, owner);
                    if (!known.contains(member)) throw new AssertionError(mixin + " uses a member unknown to official PFM: " + member);
                    checked++;
                }
            }
            for (FieldNode field : node.fields)
                if (!known.contains(target(mixin) + "." + field.name + ":" + field.desc))
                    throw new AssertionError(mixin + " shadows an unknown field " + field.name);
        }
        if (checked < 8) throw new AssertionError("Too few stub references were checked: " + checked);
    }

    private static void checkServerSafe(String mixin, String type) {
        if (!COMMON_MIXINS.contains(mixin) || !type.startsWith("net/minecraft/")) return;
        if (!COMMON_MINECRAFT.contains(type.substring("net/minecraft/".length())))
            throw new AssertionError(mixin + " is loaded on dedicated servers but names " + type);
    }

    private static String target(String mixin) {
        return switch (mixin) {
            case "PfmPlateRendererMixin" -> PLATE_RENDERER;
            case "PfmTrashcanRendererMixin" -> TRASHCAN_RENDERER;
            case "PfmMicrowaveRendererMixin" -> MICROWAVE_RENDERER;
            default -> throw new AssertionError(mixin + " must not declare fields");
        };
    }

    private static void expectHandlerCalls(byte[] bytes, String method, int expected) {
        int calls = 0;
        for (MethodNode node : node(bytes).methods)
            if (node.name.equals(method))
                for (AbstractInsnNode insn : node.instructions)
                    if (insn instanceof MethodInsnNode call && call.name.contains("minefed$")) calls++;
        if (calls != expected) throw new AssertionError(method + " calls " + calls + " Minefed handlers, expected " + expected);
    }

    // ---------------------------------------------------------------- stove and stovetop

    /** Runs the same tick sequence on original and transformed code and compares the complete event log. */
    private static void checkCooking(Path output, Path fixtures, String target, byte[] original, byte[] fixed) throws Exception {
        String simple = target.substring(target.lastIndexOf('/') + 1);
        Set<String> fields = Set.of("itemsBeingCooked", "cookingTimes", "cookingTotalTimes");
        Predicate<MethodNode> keep = m -> m.name.equals("litServerTick") || m.name.startsWith("lambda$litServerTick") || generated(m);
        int[][] scenarios = {
            // pop, rounds, then {item, cookingTime, totalTime} for each of the four slots
            {0, 6, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0},   // raw -> cooked, then a finished item stays on the stove
            {0, 3, 5, 600, 10, 1, 0, 2, 0, 0, 0, 1, 0, 50},// mixed: finished, converting, empty, still cooking
            {0, 3, 3, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0},   // a result that has its own recipe converts again
            {1, 2, 5, 600, 10, 1, 0, 2, 0, 0, 0, 0, 0, 0},// popping finished items still sends every change
            {1, 3, 1, 0, 2, 5, 0, 0, 0, 0, 0, 0, 0, 0},
        };
        int skipped = 0;
        for (int[] scenario : scenarios) {
            List<String> before = runCooking(output.resolve("cooking/" + simple + "-original"), fixtures, target, original, fields, keep, scenario);
            List<String> after = runCooking(output.resolve("cooking/" + simple + "-fixed"), fixtures, target, fixed, fields, keep, scenario);
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < before.size(); i++) {
                // An identical keep-on-stove store is followed directly by its block update; drop exactly those two.
                if (before.get(i).startsWith("set ") && before.get(i).endsWith(" same")) {
                    if (!before.get(i + 1).startsWith("send ")) throw new AssertionError("Unexpected PFM order " + before);
                    i++;
                    skipped++;
                    continue;
                }
                expected.add(before.get(i));
            }
            if (scenario[0] == 1 && !expected.equals(before)) throw new AssertionError("Pop-off behavior changed");
            if (!after.equals(expected))
                throw new AssertionError(simple + " changed beyond unchanged stores:\noriginal " + before + "\nfixed    " + after);
        }
        if (skipped < 6) throw new AssertionError("Scenarios did not exercise unchanged stores: " + skipped);
    }

    private static List<String> runCooking(Path classes, Path fixtures, String target, byte[] bytes, Set<String> fields,
            Predicate<MethodNode> keep, int[] scenario) throws Exception {
        write(classes, fixtures, target, isolate(bytes, fields::contains, keep, null, List.of()));
        try (URLClassLoader loader = loader(classes)) {
            Class<?> type = loader.loadClass(target.replace('/', '.'));
            Class<?> stack = loader.loadClass("net.minecraft.class_1799"), list = loader.loadClass("net.minecraft.class_2371");
            Class<?> level = loader.loadClass("net.minecraft.class_1937");
            loader.loadClass("com.unlikepaladin.pfm.PaladinFurnitureMod").getField("popOff").setBoolean(null, scenario[0] == 1);
            @SuppressWarnings("unchecked") List<String> log = (List<String>) level.getField("LOG").get(null);
            Object entity = type.getConstructor().newInstance();
            Object items = list.getMethod("of", int.class).invoke(null, 4);
            int[] times = new int[4], totals = new int[4];
            for (int slot = 0; slot < 4; slot++) {
                ((List<Object>) items).set(slot, stack.getConstructor(int.class).newInstance(scenario[2 + slot * 3]));
                times[slot] = scenario[3 + slot * 3];
                totals[slot] = scenario[4 + slot * 3];
            }
            set(type, entity, "itemsBeingCooked", items);
            set(type, entity, "cookingTimes", times);
            set(type, entity, "cookingTotalTimes", totals);
            list.getField("log").set(null, log);
            log.clear();
            Object world = level.getConstructor().newInstance(), pos = loader.loadClass("net.minecraft.class_2338").getConstructor().newInstance();
            Object state = loader.loadClass("net.minecraft.class_2680").getConstructor().newInstance();
            Method tick = null;
            for (Method method : type.getMethods()) if (method.getName().equals("litServerTick")) tick = method;
            for (int round = 0; round < scenario[1]; round++) {
                tick.invoke(null, world, pos, state, entity);
                log.add("state " + items + " " + Arrays.toString(times) + " " + Arrays.toString(totals));
            }
            return new ArrayList<>(log);
        }
    }

    // ---------------------------------------------------------------- microwave server tick

    private static void checkMicrowaveTick(Path output, Path fixtures, byte[] original, byte[] fixed) throws Exception {
        // {active, item, cookTime}
        int[][] scenarios = {{0, 1, 5}, {0, 7, 0}, {1, 7, 3}, {1, 0, 4}, {0, 0, 0}};
        for (int[] scenario : scenarios) {
            List<String> before = runMicrowave(output.resolve("microwave/original"), fixtures, original, scenario);
            List<String> after = runMicrowave(output.resolve("microwave/fixed"), fixtures, fixed, scenario);
            List<String> expected = new ArrayList<>(before);
            if (scenario[0] == 0 && scenario[1] != 0) expected.remove("lookup");
            if (!after.equals(expected)) throw new AssertionError("Microwave tick changed:\noriginal " + before + "\nfixed    " + after);
            if (scenario[0] == 0 && scenario[1] != 0 && !before.contains("lookup")) throw new AssertionError("Idle lookup was not reproduced");
        }
    }

    private static List<String> runMicrowave(Path classes, Path fixtures, byte[] bytes, int[] scenario) throws Exception {
        write(classes, fixtures, MICROWAVE, isolateMicrowave(bytes));
        try (URLClassLoader loader = loader(classes)) {
            Class<?> type = loader.loadClass(MICROWAVE.replace('/', '.')), level = loader.loadClass("net.minecraft.class_1937");
            @SuppressWarnings("unchecked") List<String> log = (List<String>) level.getField("LOG").get(null);
            Object microwave = newMicrowave(loader, scenario[1]);
            type.getField("isActive").setBoolean(microwave, scenario[0] == 1);
            set(type, microwave, "cookTime", scenario[2]);
            set(type, microwave, "cookTimeTotal", 10);
            log.clear();
            Object world = level.getConstructor().newInstance(), pos = loader.loadClass("net.minecraft.class_2338").getConstructor().newInstance();
            Object state = loader.loadClass("net.minecraft.class_2680").getConstructor().newInstance();
            type.getMethod("tick", level, pos.getClass(), state.getClass(), type).invoke(null, world, pos, state, microwave);
            log.add("cookTime " + get(type, microwave, "cookTime") + " active " + type.getField("isActive").getBoolean(microwave));
            return new ArrayList<>(log);
        }
    }

    private static Object newMicrowave(ClassLoader loader, int item) throws Exception {
        Class<?> type = loader.loadClass(MICROWAVE.replace('/', '.'));
        Class<?> list = loader.loadClass("net.minecraft.class_2371"), stack = loader.loadClass("net.minecraft.class_1799");
        Object microwave = type.getConstructor().newInstance();
        Object container = list.getMethod("of", int.class).invoke(null, 1);
        @SuppressWarnings("unchecked") List<Object> slots = (List<Object>) container;
        slots.set(0, stack.getConstructor(int.class).newInstance(item));
        set(type, microwave, "container", container);
        set(type, microwave, "recipeType", loader.loadClass("net.minecraft.class_3956").getField("field_17549").get(null));
        return microwave;
    }

    private static byte[] isolateMicrowave(byte[] bytes) {
        Predicate<MethodNode> keep = m -> Set.of("tick", "method_5438", "canAcceptRecipeOutput", "setActiveonClient").contains(m.name)
            || m.name.startsWith("lambda$tick") || generated(m);
        Set<String> fields = Set.of("isActive", "container", "recipeType", "cookTime", "cookTimeTotal");
        return isolate(bytes, fields::contains, keep, null, List.of("net/minecraft/class_1263"));
    }

    // ---------------------------------------------------------------- client renderers

    /**
     * Fixtures make the first Minecraft call after PFM's own HEAD code throw "original body". A render call
     * that returns therefore stopped before any drawing work; one that throws continued into PFM's code.
     */
    private static void checkRenderers(Path output, Path fixtures, Map<String, byte[]> pfm, Map<String, byte[]> fixed) throws Exception {
        for (boolean patched : new boolean[]{false, true}) {
            Path classes = output.resolve(patched ? "render/fixed" : "render/original");
            Predicate<MethodNode> keep = m -> (m.name.equals("render") && !m.desc.startsWith("(Lnet/minecraft/class_2586;")) || generated(m);
            for (String renderer : List.of(PLATE_RENDERER, TRASHCAN_RENDERER, MICROWAVE_RENDERER))
                write(classes, fixtures, renderer, isolate((patched ? fixed : pfm).get(renderer), "itemStack"::equals, keep, "java/lang/Object", List.of()));
            write(classes, fixtures, MICROWAVE, isolateMicrowave(pfm.get(MICROWAVE)));
            try (URLClassLoader loader = loader(classes)) {
                Class<?> stack = loader.loadClass("net.minecraft.class_1799");
                Object empty = stack.getConstructor(int.class).newInstance(0), food = stack.getConstructor(int.class).newInstance(1);

                Class<?> plate = loader.loadClass(BE.replace('/', '.') + "PlateBlockEntity");
                Object emptyPlate = plate.getConstructor().newInstance(), fullPlate = plate.getConstructor().newInstance();
                plate.getField("stack").set(emptyPlate, empty);
                plate.getField("stack").set(fullPlate, food);
                expectRender(loader, PLATE_RENDERER, emptyPlate, !patched, empty);
                expectRender(loader, PLATE_RENDERER, fullPlate, true, null);

                Object idle = newMicrowave(loader, 0), running = newMicrowave(loader, 0), loaded = newMicrowave(loader, 1);
                Class<?> microwave = idle.getClass();
                microwave.getField("isActive").setBoolean(running, true);
                expectRender(loader, MICROWAVE_RENDERER, idle, !patched, ((List<?>) get(microwave, idle, "container")).get(0));
                expectRender(loader, MICROWAVE_RENDERER, running, !patched, ((List<?>) get(microwave, running, "container")).get(0));
                expectRender(loader, MICROWAVE_RENDERER, loaded, true, null);

                Class<?> can = loader.loadClass(BE.replace('/', '.') + "TrashcanBlockEntity");
                Class<?> blockType = loader.loadClass("net.minecraft.class_2248"), trashcanBlock = loader.loadClass("com.unlikepaladin.pfm.blocks.TrashcanBlock");
                for (int filled = -1; filled < 9; filled++) {
                    for (boolean outer : new boolean[]{false, true}) {
                        Object trashcan = can.getConstructor().newInstance();
                        Object[] items = (Object[]) can.getField("items").get(trashcan);
                        for (int slot = 0; slot < 9; slot++) items[slot] = stack.getConstructor(int.class).newInstance(slot == filled ? 3 : 0);
                        Object block = (outer ? trashcanBlock : blockType).getConstructor().newInstance();
                        Object state = loader.loadClass("net.minecraft.class_2680").getConstructor().newInstance();
                        state.getClass().getField("block").set(state, block);
                        loader.loadClass("net.minecraft.class_2586").getField("state").set(trashcan, state);
                        // PFM skips outer trash can blocks entirely and leaves its field untouched.
                        boolean draws = !outer && (filled >= 0 || !patched);
                        expectRender(loader, TRASHCAN_RENDERER, trashcan, draws, outer ? null : items[8]);
                    }
                }
            }
        }
    }

    private static void expectRender(ClassLoader loader, String renderer, Object entity, boolean continues, Object field) throws Exception {
        Class<?> type = loader.loadClass(renderer.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Object pose = loader.loadClass("net.minecraft.class_4587").getConstructor().newInstance();
        Method render = null;
        for (Method method : type.getMethods()) if (method.getName().equals("render")) render = method;
        boolean reached = false;
        try {
            render.invoke(instance, entity, 0.5f, pose, null, 15, 0);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalStateException) || !"original body".equals(e.getCause().getMessage())) throw e;
            reached = true;
        }
        if (reached != continues) throw new AssertionError(renderer + " early exit mismatch for " + entity);
        if (!continues && type.getField("itemStack").get(instance) != field) throw new AssertionError(renderer + " left a different stack field");
    }

    // ---------------------------------------------------------------- class isolation helpers

    /** Merged Minefed handlers and MixinExtras operation bridges. */
    static boolean generated(MethodNode method) {
        return method.name.contains("minefed") || method.name.contains("mixinextras");
    }

    /** Keeps the tested members only, replaces constructors with a no-argument one and optionally rebases the class. */
    static byte[] isolate(byte[] input, Predicate<String> fields, Predicate<MethodNode> methods, String superName, List<String> interfaces) {
        ClassNode node = node(input);
        node.signature = null;
        if (superName != null) node.superName = superName;
        node.interfaces = new ArrayList<>(interfaces);
        node.innerClasses.clear();
        node.fields.removeIf(field -> !fields.test(field.name));
        node.methods.removeIf(method -> method.name.equals("<init>") || method.name.equals("<clinit>") || !methods.test(method));
        for (FieldNode field : node.fields) field.access &= ~Opcodes.ACC_FINAL;
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        node.methods.add(init);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void write(Path classes, Path fixtures, String name, byte[] bytes) throws IOException {
        if (!Files.exists(classes.resolve("net/minecraft/class_1799.class"))) {
            try (var files = Files.walk(fixtures)) {
                for (Path path : files.toList()) {
                    if (!Files.isRegularFile(path)) continue;
                    Path destination = classes.resolve(fixtures.relativize(path));
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path destination = classes.resolve(name + ".class");
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
    }

    /** MixinExtras defines its generated reference classes next to itself, so fixtures delegate to the probe loader. */
    private static URLClassLoader loader(Path classes) throws MalformedURLException {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, PfmPerformanceProbe.class.getClassLoader());
    }

    static void set(Class<?> type, Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    static Object get(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    // ---------------------------------------------------------------- fixtures

    private static void compileFixtures(Path classes) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        String mc = "package net.minecraft; ";
        sources.put("net.minecraft.class_1799", mc + """
            public final class class_1799 {
                public static final class_1799 field_8037 = new class_1799(0);
                public final int id;
                public class_1799(int id) { this.id = id; }
                public boolean method_7960() { return id == 0; }
                public String toString() { return "item" + id; }
            }
            """);
        sources.put("net.minecraft.class_2371", mc + """
            public class class_2371 extends java.util.ArrayList<Object> {
                public static java.util.List<String> log;
                public static class_2371 of(int size) { class_2371 list = new class_2371(); for (int i = 0; i < size; i++) list.add(class_1799.field_8037); return list; }
                @Override public Object set(int slot, Object value) {
                    if (log != null) log.add("set " + slot + " " + value + (get(slot) == value ? " same" : ""));
                    return super.set(slot, value);
                }
                @Override public String toString() { StringBuilder s = new StringBuilder(); for (Object o : this) s.append(((class_1799) o).id).append(','); return s.toString(); }
            }
            """);
        sources.put("net.minecraft.class_1263", mc + "public interface class_1263 { default class_1799 method_5438(int slot) { throw new AssertionError(); } }");
        sources.put("net.minecraft.class_1277", mc + """
            public class class_1277 implements class_1263 {
                private final class_1799[] items;
                public class_1277(class_1799... items) { this.items = items; }
                public class_1799 method_5438(int slot) { return items[slot]; }
            }
            """);
        sources.put("net.minecraft.class_3956", mc + "public interface class_3956<T> { class_3956<?> field_17549 = new class_3956<>() {}; }");
        sources.put("net.minecraft.class_5455", mc + "public interface class_5455 {}");
        sources.put("net.minecraft.class_1860", mc + "public interface class_1860 {}");
        sources.put("net.minecraft.class_3920", mc + """
            public class class_3920 implements class_1860 {
                private final int result;
                public class_3920(int result) { this.result = result; }
                public class_1799 method_8116(class_1263 input, class_5455 registries) { return new class_1799(result); }
            }
            """);
        sources.put("net.minecraft.class_8786", mc + """
            public final class class_8786 {
                private final class_1860 value;
                public class_8786(class_1860 value) { this.value = value; }
                public class_1860 comp_1933() { return value; }
            }
            """);
        sources.put("net.minecraft.class_1863", mc + """
            public class class_1863 {
                /** Raw item 1 cooks to 2, item 3 to 1 and item 4 to 3; every other item has no recipe. */
                public java.util.Optional<class_8786> method_8132(class_3956<?> type, class_1263 input, class_1937 level) {
                    class_1937.LOG.add("lookup");
                    int result = switch (input.method_5438(0).id) { case 1 -> 2; case 3 -> 1; case 4 -> 3; default -> 0; };
                    return result == 0 ? java.util.Optional.empty() : java.util.Optional.of(new class_8786(new class_3920(result)));
                }
            }
            """);
        sources.put("net.minecraft.class_1920", mc + "public interface class_1920 {}");
        sources.put("net.minecraft.class_1937", mc + """
            public class class_1937 implements class_1920 {
                public static final java.util.List<String> LOG = new java.util.ArrayList<>();
                private final class_1863 recipes = new class_1863();
                public class_1863 method_8433() { return recipes; }
                public class_5455 method_30349() { return null; }
                public void method_8413(class_2338 pos, class_2680 oldState, class_2680 newState, int flags) { LOG.add("send " + flags); }
            }
            """);
        sources.put("net.minecraft.class_2338", mc + """
            public class class_2338 {
                public int method_10263() { return 1; }
                public int method_10264() { return 2; }
                public int method_10260() { return 3; }
                public class_2338 method_10084() { return this; }
            }
            """);
        sources.put("net.minecraft.class_2248", mc + "public class class_2248 {}");
        sources.put("net.minecraft.class_2769", mc + "public class class_2769 {}");
        sources.put("net.minecraft.class_2753", mc + "public class class_2753 extends class_2769 {}");
        sources.put("net.minecraft.class_2746", mc + "public class class_2746 extends class_2769 {}");
        sources.put("net.minecraft.class_2680", mc + """
            public class class_2680 {
                public class_2248 block;
                public class_2248 method_26204() { return block; }
                public Comparable<?> method_11654(class_2769 property) { throw new IllegalStateException("original body"); }
            }
            """);
        sources.put("net.minecraft.class_1264", mc + """
            public class class_1264 {
                public static void method_5449(class_1937 level, double x, double y, double z, class_1799 stack) { class_1937.LOG.add("drop " + stack.id); }
            }
            """);
        sources.put("net.minecraft.class_2586", mc + """
            public abstract class class_2586 {
                public class_2680 state;
                public class_2680 method_11010() { return state; }
                public static void method_31663(class_1937 level, class_2338 pos, class_2680 state) { class_1937.LOG.add("setChanged"); }
            }
            """);
        sources.put("net.minecraft.class_3924", mc + "public class class_3924 extends class_2586 {}");
        sources.put("net.minecraft.class_2624", mc + "public class class_2624 extends class_2586 { public int method_5444() { return 64; } }");
        sources.put("net.minecraft.class_4587", mc + "public class class_4587 { public void method_22903() { throw new IllegalStateException(\"original body\"); } }");
        sources.put("net.minecraft.class_4597", mc + "public interface class_4597 {}");
        String pfm = "package com.unlikepaladin.pfm";
        sources.put("com.unlikepaladin.pfm.PaladinFurnitureMod", pfm + """
            ; public class PaladinFurnitureMod {
                public static boolean popOff;
                public static com.unlikepaladin.pfm.config.PaladinFurnitureModConfig getPFMConfig() { return new com.unlikepaladin.pfm.config.PaladinFurnitureModConfig(); }
            }
            """);
        sources.put("com.unlikepaladin.pfm.config.PaladinFurnitureModConfig", pfm + """
            .config; public class PaladinFurnitureModConfig { public boolean doesFoodPopOffStove() { return com.unlikepaladin.pfm.PaladinFurnitureMod.popOff; } }
            """);
        sources.put("com.unlikepaladin.pfm.blocks.blockentities.OvenBlockEntity", pfm + """
            .blocks.blockentities; import net.minecraft.*;
            public class OvenBlockEntity extends class_2624 {
                public static void serverTick(class_1937 level, class_2338 pos, class_2680 state, OvenBlockEntity oven) { class_1937.LOG.add("serverTick"); }
            }
            """);
        // Placeholder for compilation; replaced by the isolated official class before each run.
        sources.put("com.unlikepaladin.pfm.blocks.blockentities.MicrowaveBlockEntity", pfm + ".blocks.blockentities; public class MicrowaveBlockEntity {}");
        sources.put("com.unlikepaladin.pfm.blocks.blockentities.fabric.MicrowaveBlockEntityImpl", pfm + """
            .blocks.blockentities.fabric; import com.unlikepaladin.pfm.blocks.blockentities.MicrowaveBlockEntity;
            public class MicrowaveBlockEntityImpl {
                public static void setActiveonClient(MicrowaveBlockEntity microwave, boolean active) { net.minecraft.class_1937.LOG.add("client active " + active); }
            }
            """);
        sources.put("com.unlikepaladin.pfm.blocks.blockentities.PlateBlockEntity", pfm + """
            .blocks.blockentities; import net.minecraft.*;
            public class PlateBlockEntity extends class_2586 {
                public class_1799 stack;
                public class_1799 getItemInPlate() { return stack; }
                @Override public class_2680 method_11010() { throw new IllegalStateException("original body"); }
            }
            """);
        sources.put("com.unlikepaladin.pfm.blocks.blockentities.TrashcanBlockEntity", pfm + """
            .blocks.blockentities; import net.minecraft.*;
            public class TrashcanBlockEntity extends class_2586 {
                public final class_1799[] items = new class_1799[9];
                public class_1799 method_5438(int slot) { return items[slot]; }
            }
            """);
        sources.put("com.unlikepaladin.pfm.blocks.TrashcanBlock", pfm + ".blocks; public class TrashcanBlock extends net.minecraft.class_2248 {}");
        sources.put("com.unlikepaladin.pfm.blocks.InnerTrashcanBlock", pfm + """
            .blocks; public class InnerTrashcanBlock extends net.minecraft.class_2248 { public static final net.minecraft.class_2753 FACING = new net.minecraft.class_2753(); }
            """);
        Files.createDirectories(classes);
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString()));
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
