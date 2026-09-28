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
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.Type;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/**
 * Applies the shipped TrafficCraft performance Mixins to the official TrafficCraft 1.20.4-1.1.3 classes
 * (with its bundled DragonLib 1.20.4-2.2.24) and compares original and transformed code on small fixtures.
 */
public final class TrafficCraftPerformanceProbe {
    private static final String SHA256 = "ddf1d434be7eee0ab7ff67ba4712cb213900bc10c43776cc52dddc40697a0a58";
    private static final String DRAGONLIB = "META-INF/jars/dragonlib-fabric-1.20.4-2.2.24.jar";
    private static final String DRAGONLIB_SHA256 = "c53a94bb2d1e37e0146c37f018153394697221b40cf60ae4f4edb897a00a530f";
    private static final String TC = "de/mrjulsen/trafficcraft/", DL = "de/mrjulsen/mcdragonlib/";
    private static final String LIGHT_BE = TC + "block/entity/TrafficLightBlockEntity", SIGN_BE = TC + "block/entity/TrafficSignBlockEntity",
        BUTTON_BE = TC + "block/entity/TrafficLightRequestButtonBlockEntity", SYNCED = DL + "block/SyncedBlockEntity";
    private static final String LIGHT = TC + "block/TrafficLightBlock", POST = TC + "block/TrafficSignPostBlock", SALT = TC + "block/RoadSaltBlock";
    private static final String TEXTURES = TC + "client/TrafficLightTextureManager", BULB = TEXTURES + "$TrafficLightBulbModel",
        KEY = TEXTURES + "$TrafficLightTextureKey", RESET_PACKET = TC + "network/packets/stc/TrafficSignTextureResetPacket";
    private static final String MIXINS = "team/minefed/mods/clientcompat/mixin/", HELPERS = "team/minefed/mods/clientcompat/trafficcraft/";
    private static final String COMMON_CONFIG = "minefed-trafficcraft-perf.mixins.json", CLIENT_CONFIG = "minefed-trafficcraft-render-perf.mixins.json";
    private static final List<String> COMMON_MIXINS = List.of("TrafficCraftShapeCacheMixin", "TrafficLightUpdateMixin", "TrafficSignResetRecipientsMixin");
    private static final List<String> CLIENT_MIXINS = List.of("TrafficLightBulbModelInvoker", "TrafficLightTextureLookupMixin");
    private static final List<String> HELPER_CLASSES = List.of("StateShapeCache", "BulbModelCache");
    /** Minecraft classes the dedicated-server Mixins and their helper may name; all are common classes. */
    private static final Set<String> COMMON_MINECRAFT = Set.of("class_1922", "class_1923", "class_1937", "class_2248", "class_2338", "class_2586",
        "class_265", "class_2680", "class_3215", "class_3218", "class_3726", "class_3898");
    private static final String SHAPE = "method_9530";
    private static final String SHAPE_DESC = "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;Lnet/minecraft/class_3726;)Lnet/minecraft/class_265;";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: TrafficCraftPerformanceProbe <official TrafficCraft JAR> <output>");
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        if (!SHA256.equals(sha256(Files.readAllBytes(jar)))) throw new IllegalArgumentException("Expected the official TrafficCraft Fabric 1.20.4-1.1.3 artifact");
        Map<String, byte[]> official = new HashMap<>();
        byte[] dragonlib;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            readClasses(new FileInputStream(jar.toFile()), official);
            try (InputStream nested = zip.getInputStream(zip.getEntry(DRAGONLIB))) { dragonlib = nested.readAllBytes(); }
        }
        if (!DRAGONLIB_SHA256.equals(sha256(dragonlib))) throw new IllegalArgumentException("Unexpected bundled DragonLib");
        readClasses(new ByteArrayInputStream(dragonlib), official);

        checkConfigurations();
        checkReferences(official);
        checkShapeSources(official);
        checkLookupSources(official);
        checkSyncSources(official);
        checkResetHandler(official);

        if (Files.exists(output)) try (var files = Files.walk(output)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Path fixtures = output.resolve("fixtures");
        compileFixtures(fixtures);
        MixinCompatProbe.ProbeService.officialClasses = official;
        MixinCompatProbe.ProbeService.fixtureClasses = fixtures;
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(COMMON_CONFIG);
        Mixins.addConfiguration(CLIENT_CONFIG);
        var service = (MixinCompatProbe.ProbeService) MixinService.getService();
        IMixinTransformer transformer = service.factory().createTransformer();
        MixinExtrasBootstrap.init();
        Map<String, byte[]> fixed = new HashMap<>();
        for (String target : List.of(LIGHT_BE, SIGN_BE, LIGHT, POST, SALT, TEXTURES, BULB)) {
            String name = target.replace('/', '.');
            byte[] result = transformer.transformClassBytes(name, name, official.get(target));
            if (Arrays.equals(result, official.get(target))) throw new AssertionError("Mixin was not applied to " + name);
            fixed.put(target, result);
        }
        expectHandlerCalls(fixed.get(LIGHT_BE), "setPowered", 2);
        expectHandlerCalls(fixed.get(LIGHT_BE), "stopSchedule", 2);
        expectHandlerCalls(fixed.get(LIGHT_BE), "enableOnlyColors", 2);
        for (String block : List.of(LIGHT, POST, SALT)) expectHandlerCalls(fixed.get(block), SHAPE, 1);
        expectHandlerCalls(fixed.get(SIGN_BE), "setAndResetTexture", 1);
        expectHandlerCalls(fixed.get(TEXTURES), "render", 2);
        if (!node(fixed.get(BULB)).interfaces.contains(MIXINS + "TrafficLightBulbModelInvoker"))
            throw new AssertionError("Bulb model invoker was not applied");

        int skipped = checkLightUpdates(output, fixtures, official, fixed);
        int cached = checkShapes(output, fixtures, official, fixed);
        checkResetRecipients(output, fixtures, official, fixed);
        int lookups = checkBulbLookups(output, fixtures, official, fixed);
        System.out.println("PASS: shipped TrafficCraft performance Mixins apply to official TrafficCraft 1.20.4-1.1.3 / DragonLib 1.20.4-2.2.24 bytecode; "
            + skipped + " unchanged traffic-light notifications keep setChanged and drop only their packets, " + cached
            + " block states return one shape equal to TrafficCraft's, texture resets reach exactly the chunk's players, and "
            + lookups + " bulb lookups draw the model TrafficCraft selects.");
        System.out.println("Scope: real TrafficCraft/DragonLib bytecode and Sponge/MixinExtras transformation; Minecraft classes are fixtures. "
            + "Packet volume and frame time must be measured in game.");
    }

    // ---------------------------------------------------------------- static checks

    private static void checkConfigurations() throws IOException {
        JsonObject mod = PfmPerformanceProbe.json("fabric.mod.json");
        if (!"*".equals(mod.get("environment").getAsString())) throw new AssertionError("The mod must load on dedicated servers");
        Map<String, String> environments = new HashMap<>();
        for (JsonElement entry : mod.getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive()) environments.put(entry.getAsString(), "*");
            else environments.put(entry.getAsJsonObject().get("config").getAsString(), entry.getAsJsonObject().get("environment").getAsString());
        }
        if (!"*".equals(environments.get(COMMON_CONFIG))) throw new AssertionError(COMMON_CONFIG + " is not loaded on servers");
        if (!"client".equals(environments.get(CLIENT_CONFIG))) throw new AssertionError(CLIENT_CONFIG + " must stay client-only");
        if (!"=1.20.4-1.1.3".equals(mod.getAsJsonObject("depends").get("trafficcraft").getAsString()))
            throw new AssertionError("The TrafficCraft version must stay pinned");
        expectConfig(COMMON_CONFIG, "mixins", COMMON_MIXINS);
        expectConfig(CLIENT_CONFIG, "client", CLIENT_MIXINS);
    }

    private static void expectConfig(String name, String section, List<String> classes) throws IOException {
        JsonObject config = PfmPerformanceProbe.json(name);
        List<String> listed = new ArrayList<>();
        for (JsonElement element : config.getAsJsonArray(section)) listed.add(element.getAsString());
        for (String other : List.of("mixins", "client", "server"))
            if (!other.equals(section) && config.has(other)) throw new AssertionError(name + " must not list " + other + " Mixins");
        if (!listed.equals(classes) || !config.get("required").getAsBoolean()) throw new AssertionError("Unexpected " + name);
    }

    /**
     * The Mixins compile against signature stubs. Every Minecraft/TrafficCraft/DragonLib member they or their helpers
     * use must be one the official bytecode declares or references with the same owner and descriptor.
     */
    private static void checkReferences(Map<String, byte[]> official) throws IOException {
        Set<String> known = new HashSet<>();
        for (byte[] bytes : official.values()) {
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
        List<String> all = new ArrayList<>();
        for (String mixin : COMMON_MIXINS) all.add(MIXINS + mixin);
        for (String mixin : CLIENT_MIXINS) all.add(MIXINS + mixin);
        for (String helper : HELPER_CLASSES) all.add(HELPERS + helper);
        for (String type : all) {
            ClassNode node = node(resource(type + ".class"));
            boolean server = COMMON_MIXINS.stream().anyMatch(m -> type.equals(MIXINS + m)) || type.equals(HELPERS + "StateShapeCache");
            for (FieldNode field : node.fields) {
                List<AnnotationNode> annotations = new ArrayList<>();
                if (field.visibleAnnotations != null) annotations.addAll(field.visibleAnnotations);
                if (field.invisibleAnnotations != null) annotations.addAll(field.invisibleAnnotations);
                if (annotations.stream().anyMatch(a -> a.desc.endsWith("/Shadow;"))) {
                    if (!known.contains(LIGHT_BE + "." + field.name + ":" + field.desc)) throw new AssertionError(type + " shadows an unknown field " + field.name);
                    checked++;
                }
                checkServerSafe(server, type, Type.getType(field.desc));
            }
            for (MethodNode method : node.methods) {
                for (Type argument : Type.getArgumentTypes(method.desc)) checkServerSafe(server, type, argument);
                checkServerSafe(server, type, Type.getReturnType(method.desc));
                for (AbstractInsnNode insn : method.instructions) {
                    String owner = null, member = null;
                    if (insn instanceof MethodInsnNode call) { owner = call.owner; member = call.owner + "." + call.name + call.desc; }
                    if (insn instanceof FieldInsnNode field && !field.owner.equals(node.name)) {
                        owner = field.owner; member = field.owner + "." + field.name + ":" + field.desc;
                    }
                    if (insn instanceof TypeInsnNode typeInsn) checkServerSafe(server, type, Type.getObjectType(typeInsn.desc));
                    if (owner == null || !(owner.startsWith("net/minecraft/") || owner.startsWith(TC) || owner.startsWith(DL))) continue;
                    checkServerSafe(server, type, Type.getObjectType(owner));
                    if (!known.contains(member)) throw new AssertionError(type + " uses a member unknown to official TrafficCraft/DragonLib: " + member);
                    checked++;
                }
            }
        }
        if (!known.contains(BULB + ".render(Lde/mrjulsen/mcdragonlib/client/ber/BERGraphics;Lnet/minecraft/class_2586;I)V"))
            throw new AssertionError("Invoker target is missing");
        if (checked < 12) throw new AssertionError("Too few stub references were checked: " + checked);
    }

    /** Server Mixins must name only common Minecraft classes and no TrafficCraft/DragonLib client classes. */
    private static void checkServerSafe(boolean server, String mixin, Type type) {
        if (!server) return;
        while (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() != Type.OBJECT) return;
        String name = type.getInternalName();
        if (name.startsWith("net/minecraft/") && !COMMON_MINECRAFT.contains(name.substring("net/minecraft/".length())))
            throw new AssertionError(mixin + " is loaded on dedicated servers but names " + name);
        if (name.startsWith(TC + "client/") || name.startsWith(DL + "client/"))
            throw new AssertionError(mixin + " is loaded on dedicated servers but names " + name);
    }

    /** The memoized getShape methods read only their state and constant shapes, and no class extends them. */
    private static void checkShapeSources(Map<String, byte[]> official) {
        Set<String> targets = Set.of(LIGHT, POST, SALT, LIGHT_BE, SIGN_BE, TEXTURES, BULB, KEY);
        for (byte[] bytes : official.values()) {
            ClassNode node = node(bytes);
            if (targets.contains(node.superName)) throw new AssertionError(node.name + " extends patched class " + node.superName);
        }
        Set<String> allowed = Set.of("net/minecraft/class_2680.method_11654", "java/util/Map.get", "java/lang/Boolean.booleanValue",
            "net/minecraft/class_259.method_1084", "net/minecraft/class_259.method_17786", "net/minecraft/class_2248.method_9541");
        for (String block : List.of(LIGHT, POST, SALT)) {
            ClassNode node = node(official.get(block));
            MethodNode shape = method(node, SHAPE, SHAPE_DESC);
            for (AbstractInsnNode insn : shape.instructions) {
                if (insn instanceof VarInsnNode local && local.var >= 2 && local.var <= 4 && local.getOpcode() == Opcodes.ALOAD)
                    throw new AssertionError(block + " getShape reads its level, position or context");
                if (insn instanceof MethodInsnNode call && !allowed.contains(call.owner + "." + call.name))
                    throw new AssertionError(block + " getShape calls " + call.owner + "." + call.name);
                if (insn instanceof FieldInsnNode field) {
                    if (field.getOpcode() != Opcodes.GETSTATIC) throw new AssertionError(block + " getShape uses instance state");
                    ClassNode owner = node(official.containsKey(field.owner) ? official.get(field.owner) : null);
                    if (owner == null) {
                        if (!field.owner.equals("net/minecraft/class_2350$class_2351")) throw new AssertionError("Unexpected constant " + field.owner);
                        continue;
                    }
                    FieldNode declared = owner.fields.stream().filter(f -> f.name.equals(field.name)).findFirst().orElseThrow();
                    if ((declared.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                        throw new AssertionError(block + "." + field.name + " is not a constant");
                }
            }
        }
        // TrafficLightBlock's private shape map is filled once during class initialization.
        ClassNode light = node(official.get(LIGHT));
        if ((light.fields.stream().filter(f -> f.name.equals("shapes")).findFirst().orElseThrow().access & Opcodes.ACC_PRIVATE) == 0)
            throw new AssertionError("TrafficLightBlock.shapes is not private");
        for (MethodNode method : light.methods) {
            boolean initializer = method.name.equals("<clinit>") || method.name.startsWith("lambda$static$");
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.name.equals("shapes") && !initializer && !method.name.equals(SHAPE))
                    throw new AssertionError("TrafficLightBlock.shapes is used by " + method.name);
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Map") && !call.name.equals("get") && !initializer)
                    throw new AssertionError("TrafficLightBlock changes a map in " + method.name);
            }
        }
    }

    /** The bulb model list is filled only by the static initializer, and key equality compares the icon and color only. */
    private static void checkLookupSources(Map<String, byte[]> official) {
        ClassNode textures = node(official.get(TEXTURES));
        FieldNode models = textures.fields.stream().filter(f -> f.name.equals("models")).findFirst().orElseThrow();
        if ((models.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
            throw new AssertionError("Model list is not a private constant");
        for (MethodNode method : textures.methods) {
            boolean initializer = method.name.equals("<clinit>") || method.name.startsWith("lambda$static$");
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/List") && !call.name.equals("stream") && !initializer)
                    throw new AssertionError("Model list is changed or exposed by " + method.name);
                if (insn instanceof FieldInsnNode field && field.name.equals("models") && field.getOpcode() == Opcodes.GETSTATIC
                        && !(insn.getNext() instanceof MethodInsnNode next && next.name.equals("stream")) && !initializer)
                    throw new AssertionError("Model list escapes from " + method.name);
            }
        }
        ClassNode key = node(official.get(KEY));
        for (FieldNode field : key.fields) if ((field.access & Opcodes.ACC_FINAL) == 0) throw new AssertionError("Mutable key field " + field.name);
        Set<String> calls = new HashSet<>();
        for (AbstractInsnNode insn : method(key, "equals", "(Ljava/lang/Object;)Z").instructions)
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        if (!calls.equals(Set.of(KEY + ".getColor", KEY + ".getIcon"))) throw new AssertionError("Key equality uses " + calls);
        for (String getter : List.of("getIcon", "getColor")) {
            List<String> body = new ArrayList<>();
            for (AbstractInsnNode insn : methodNamed(key, getter).instructions) if (insn.getOpcode() >= 0) body.add(String.valueOf(insn.getOpcode()));
            if (!body.equals(List.of(String.valueOf(Opcodes.ALOAD), String.valueOf(Opcodes.GETFIELD), String.valueOf(Opcodes.ARETURN))))
                throw new AssertionError("Key " + getter + " is not a plain field read");
        }
    }

    /**
     * Clients read the traffic light's phase, control type, icon, type, color slots, enabled colors, running flag and
     * schedule. Every official method that changes one of them also sends the update itself. The timer fields that
     * change silently every tick are private and read only through isFirstIteration by the server-side request button.
     */
    private static void checkSyncSources(Map<String, byte[]> official) {
        ClassNode light = node(official.get(LIGHT_BE));
        Set<String> visible = Set.of("phaseId", "controlType", "icon", "type", "running", "schedule");
        Set<String> mutators = Set.of("clear", "add", "addAll", "remove", "removeIf", "removeAll", "retainAll", "set");
        Map<String, Boolean> writes = new HashMap<>(), notifies = new HashMap<>();
        for (MethodNode method : light.methods) {
            String owner = method.name.startsWith("lambda$") ? method.name.split("\\$")[1] : method.name;
            boolean write = false, notify = false, slots = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && visible.contains(field.name)) write = true;
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.name.equals("colorSlots")) slots = true;
                if (slots && insn.getOpcode() == Opcodes.AASTORE) write = true;
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Collection") && mutators.contains(call.name)) write = true;
                if (insn instanceof MethodInsnNode call && call.name.equals("notifyUpdate")) notify = true;
            }
            writes.merge(owner, write, Boolean::logicalOr);
            notifies.merge(owner, notify, Boolean::logicalOr);
        }
        for (var entry : writes.entrySet()) {
            if (!entry.getValue() || Set.of("<init>", "method_11014", "load").contains(entry.getKey())) continue;
            if (!notifies.get(entry.getKey())) throw new AssertionError("TrafficLightBlockEntity." + entry.getKey() + " changes client data silently");
        }
        for (String name : List.of("ticker", "totalTicks"))
            if ((light.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow().access & Opcodes.ACC_PRIVATE) == 0)
                throw new AssertionError(name + " is not private");
        for (byte[] bytes : official.values()) {
            ClassNode node = node(bytes);
            if (node.name.equals(LIGHT_BE)) continue;
            for (MethodNode method : node.methods)
                for (AbstractInsnNode insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && call.owner.equals(LIGHT_BE) && call.name.equals("isFirstIteration")
                            && !(node.name.equals(BUTTON_BE) && method.name.equals("tick")))
                        throw new AssertionError(node.name + "." + method.name + " reads the silent timer");
        }
        // The request button reads it only after checking that its level is not client-side.
        MethodNode tick = method(node(official.get(BUTTON_BE)), "tick", "(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;)V");
        boolean serverCheck = false;
        for (AbstractInsnNode insn : tick.instructions) {
            if (insn instanceof FieldInsnNode field && field.name.equals("field_9236") && insn.getNext().getOpcode() == Opcodes.IFNE) serverCheck = true;
            if (insn instanceof MethodInsnNode call && call.name.equals("isFirstIteration") && !serverCheck)
                throw new AssertionError("The request button reads the timer on clients");
        }
    }

    /** The client reset handler only resets a TrafficSignBlockEntity it finds at the position. */
    private static void checkResetHandler(Map<String, byte[]> official) {
        MethodNode handler = null;
        for (MethodNode method : node(official.get(RESET_PACKET)).methods)
            for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof MethodInsnNode call && call.name.equals("method_8321")) handler = method;
        if (handler == null) throw new AssertionError("Reset handler not found");
        Set<String> calls = new HashSet<>();
        boolean guarded = false;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.INSTANCEOF && type.desc.equals(SIGN_BE)) guarded = true;
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner + "." + call.name);
                if (call.name.equals("resetTexture") && !guarded) throw new AssertionError("Unguarded texture reset");
            }
        }
        if (!calls.equals(Set.of("java/util/function/Supplier.get", "dev/architectury/networking/NetworkManager$PacketContext.getPlayer",
                "net/minecraft/class_1657.method_37908", "net/minecraft/class_1937.method_8321", SIGN_BE + ".resetTexture")))
            throw new AssertionError("Reset handler does more than reset the local sign: " + calls);
    }

    private static void expectHandlerCalls(byte[] bytes, String method, int expected) {
        int calls = 0;
        for (MethodNode node : node(bytes).methods)
            if (node.name.equals(method))
                for (AbstractInsnNode insn : node.instructions)
                    if (insn instanceof MethodInsnNode call && call.name.contains("minefed$")) calls++;
        if (calls != expected) throw new AssertionError(method + " calls " + calls + " Minefed handlers, expected " + expected);
    }

    // ---------------------------------------------------------------- traffic-light updates

    /** Runs setPowered, stopSchedule and enableOnlyColors on original and transformed code with the real DragonLib notifyUpdate. */
    private static int checkLightUpdates(Path output, Path fixtures, Map<String, byte[]> official, Map<String, byte[]> fixed) throws Exception {
        int skipped = 0;
        Object a = "RED", b = "YELLOW", c = "GREEN";
        List<List<Object>> lists = List.of(List.of(), List.of(a), List.of(a, b), List.of(b, a), List.of(a, a), List.of(a, b, c));
        List<Object[]> scenarios = new ArrayList<>();
        for (boolean server : new boolean[]{true, false}) {
            for (boolean before : new boolean[]{false, true})
                for (boolean value : new boolean[]{false, true})
                    scenarios.add(new Object[]{server, "setPowered", before, value});
            for (int[] timer : new int[][]{{0, 0, 0}, {1, 0, 0}, {0, 4, 0}, {0, 0, 9}, {1, 3, 7}})
                scenarios.add(new Object[]{server, "stopSchedule", timer});
            for (List<Object> before : lists) {
                for (List<Object> colors : lists) scenarios.add(new Object[]{server, "enableOnlyColors", before, new ArrayList<>(colors)});
                scenarios.add(new Object[]{server, "enableOnlyColors", before, new LinkedHashSet<>(before)});
                scenarios.add(new Object[]{server, "enableOnlyColors", before, "self"});
                scenarios.add(new Object[]{server, "enableOnlyColors", before, null});
                List<Object> copies = new ArrayList<>();
                for (Object color : before) copies.add(new String((String) color));
                scenarios.add(new Object[]{server, "enableOnlyColors", before, copies});
            }
        }
        for (Object[] scenario : scenarios) {
            List<String> original = runLight(output.resolve("light/original"), fixtures, official, official.get(LIGHT_BE), scenario);
            List<String> patched = runLight(output.resolve("light/fixed"), fixtures, official, fixed.get(LIGHT_BE), scenario);
            // The last entry is the resulting field state; "same" marks calls that left every field unchanged.
            boolean unchanged = original.remove("same");
            patched.remove("same");
            List<String> expected = new ArrayList<>(original);
            // A call that throws before notifying (a null collection) sends nothing either way.
            if (unchanged && expected.remove("blockChanged 1,2,3")) skipped++;
            if (!patched.equals(expected))
                throw new AssertionError("Traffic light " + scenario[1] + " changed:\noriginal " + original + "\nfixed    " + patched);
        }
        if (skipped < 12) throw new AssertionError("Scenarios did not exercise unchanged updates: " + skipped);
        return skipped;
    }

    @SuppressWarnings("unchecked")
    private static List<String> runLight(Path classes, Path fixtures, Map<String, byte[]> official, byte[] bytes, Object[] scenario) throws Exception {
        write(classes, fixtures, SYNCED, PfmPerformanceProbe.isolate(official.get(SYNCED), f -> false,
            m -> Set.of("notifyUpdate", "sendData").contains(m.name), null, List.of()));
        Set<String> fields = Set.of("powered", "running", "ticker", "totalTicks", "enabledColors");
        write(classes, fixtures, LIGHT_BE, PfmPerformanceProbe.isolate(bytes, fields::contains,
            m -> Set.of("setPowered", "stopSchedule", "enableOnlyColors").contains(m.name) || PfmPerformanceProbe.generated(m), SYNCED, List.of()));
        try (URLClassLoader loader = loader(classes)) {
            Class<?> type = loader.loadClass(LIGHT_BE.replace('/', '.')), level = loader.loadClass("net.minecraft.class_1937");
            List<String> log = (List<String>) level.getField("LOG").get(null);
            log.clear();
            Object entity = type.getConstructor().newInstance();
            Class<?> base = loader.loadClass("net.minecraft.class_2586");
            base.getField("field_11863").set(entity, loader.loadClass((Boolean) scenario[0] ? "net.minecraft.class_3218" : "net.minecraft.class_1937")
                .getConstructor().newInstance());
            base.getField("pos").set(entity, loader.loadClass("net.minecraft.class_2338").getConstructor(int.class, int.class, int.class).newInstance(1, 2, 3));
            List<Object> colors = new ArrayList<>();
            PfmPerformanceProbe.set(type, entity, "enabledColors", colors);
            String before;
            switch ((String) scenario[1]) {
                case "setPowered" -> {
                    PfmPerformanceProbe.set(type, entity, "powered", scenario[2]);
                    before = state(type, entity);
                    call(type, entity, "setPowered", boolean.class, scenario[3], log);
                }
                case "stopSchedule" -> {
                    int[] timer = (int[]) scenario[2];
                    PfmPerformanceProbe.set(type, entity, "running", timer[0] == 1);
                    PfmPerformanceProbe.set(type, entity, "ticker", timer[1]);
                    PfmPerformanceProbe.set(type, entity, "totalTicks", (long) timer[2]);
                    before = state(type, entity);
                    call(type, entity, "stopSchedule", null, null, log);
                }
                default -> {
                    colors.addAll((List<Object>) scenario[2]);
                    List<Object> previous = new ArrayList<>(colors);
                    before = state(type, entity);
                    call(type, entity, "enableOnlyColors", Collection.class, "self".equals(scenario[3]) ? colors : scenario[3], log);
                    // Element identity, not only equality: the new list must hold the very same objects.
                    if (previous.size() != colors.size()) before = null;
                    else for (int i = 0; i < colors.size(); i++) if (previous.get(i) != colors.get(i)) before = null;
                }
            }
            String after = state(type, entity);
            List<String> result = new ArrayList<>(log);
            result.add(after);
            if (after.equals(before)) result.add("same");
            return result;
        }
    }

    private static void call(Class<?> type, Object entity, String name, Class<?> parameter, Object argument, List<String> log) throws Exception {
        Method method = parameter == null ? type.getMethod(name) : type.getMethod(name, parameter);
        try {
            if (parameter == null) method.invoke(entity);
            else method.invoke(entity, argument);
        } catch (InvocationTargetException e) {
            log.add("throws " + e.getCause().getClass().getName());
        }
    }

    private static String state(Class<?> type, Object entity) throws ReflectiveOperationException {
        return "powered=" + PfmPerformanceProbe.get(type, entity, "powered") + " running=" + PfmPerformanceProbe.get(type, entity, "running")
            + " ticker=" + PfmPerformanceProbe.get(type, entity, "ticker") + " totalTicks=" + PfmPerformanceProbe.get(type, entity, "totalTicks")
            + " colors=" + PfmPerformanceProbe.get(type, entity, "enabledColors");
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Calls the real getShape bytecode for every state of fixture properties. The transformed method must return a
     * shape equal to the original on the first call and that same object afterwards, building it only once.
     */
    private static int checkShapes(Path output, Path fixtures, Map<String, byte[]> official, Map<String, byte[]> fixed) throws Exception {
        int states = 0;
        for (String block : List.of(LIGHT, POST, SALT)) {
            Map<String, List<String>> results = new HashMap<>();
            for (boolean patched : new boolean[]{false, true}) {
                Path classes = output.resolve("shapes/" + (patched ? "fixed" : "original"));
                ClassNode node = node(official.get(block));
                Set<String> statics = new HashSet<>();
                for (AbstractInsnNode insn : method(node, SHAPE, SHAPE_DESC).instructions)
                    if (insn instanceof FieldInsnNode field && field.owner.equals(block)) statics.add(field.name);
                write(classes, fixtures, block, PfmPerformanceProbe.isolate((patched ? fixed : official).get(block), statics::contains,
                    m -> m.name.equals(SHAPE) || PfmPerformanceProbe.generated(m), "net/minecraft/class_2248", List.of()));
                results.put(patched ? "fixed" : "original", runShapes(classes, block, patched));
            }
            List<String> original = results.get("original"), patched = results.get("fixed");
            if (!original.equals(patched)) throw new AssertionError(block + " shapes changed:\noriginal " + original + "\nfixed    " + patched);
            states += original.size() - 1;
        }
        return states;
    }

    private static List<String> runShapes(Path classes, String block, boolean patched) throws Exception {
        try (URLClassLoader loader = loader(classes)) {
            Class<?> type = loader.loadClass(block.replace('/', '.')), shapes = loader.loadClass("net.minecraft.class_259");
            Class<?> stateType = loader.loadClass("net.minecraft.class_2680"), blockType = loader.loadClass("net.minecraft.class_2248");
            Object instance = type.getConstructor().newInstance(), other = type.getConstructor().newInstance();
            Method shape = type.getMethod(SHAPE, stateType, loader.loadClass("net.minecraft.class_1922"),
                loader.loadClass("net.minecraft.class_2338"), loader.loadClass("net.minecraft.class_3726"));
            Constructor<?> newState = stateType.getConstructor(blockType, Map.class);
            List<Map<Object, Object>> properties = properties(loader, type, block);
            List<String> results = new ArrayList<>();
            for (Map<Object, Object> values : properties) {
                Object state = newState.newInstance(instance, values);
                int built = shapes.getField("built").getInt(null);
                Object first = shape.invoke(instance, state, null, null, null);
                int cost = shapes.getField("built").getInt(null) - built;
                Object second = shape.invoke(instance, state, null, null, null);
                int again = shapes.getField("built").getInt(null) - built - cost;
                if (cost == 0) throw new AssertionError(block + " built no shape");
                if (patched && (first != second || again != 0)) throw new AssertionError(block + " did not reuse the shape of " + state);
                if (!patched && (first == second || again != cost)) throw new AssertionError(block + " original shape was expected to be rebuilt");
                // Another block instance's state, and a copy of the state object, are separate cache entries.
                Object foreign = newState.newInstance(other, values), copy = newState.newInstance(instance, values);
                Object foreignShape = shape.invoke(instance, foreign, null, null, null);
                if (foreignShape == first || foreignShape == shape.invoke(instance, foreign, null, null, null))
                    throw new AssertionError(block + " cached a state of another block");
                if (shape.invoke(instance, copy, null, null, null) == first) throw new AssertionError(block + " mixed two state objects");
                results.add(values + " -> " + first);
            }
            // A null state reaches TrafficCraft's code unchanged (RoadSaltBlock ignores it; the others throw).
            try {
                results.add("null -> " + shape.invoke(instance, null, null, null, null));
            } catch (InvocationTargetException e) {
                results.add("null -> throws " + e.getCause().getClass().getName());
            }
            return results;
        }
    }

    /** Every combination of the properties getShape reads, using fixture property objects in the block's static fields. */
    private static List<Map<Object, Object>> properties(ClassLoader loader, Class<?> type, String block) throws Exception {
        List<Map<Object, Object>> states = new ArrayList<>();
        Class<?> property = loader.loadClass("net.minecraft.class_2769"), enumProperty = loader.loadClass("net.minecraft.class_2754"),
            directionProperty = loader.loadClass("net.minecraft.class_2753"), booleanProperty = loader.loadClass("net.minecraft.class_2746"),
            shape = loader.loadClass("net.minecraft.class_265"), direction = loader.loadClass("net.minecraft.class_2350"),
            axis = loader.loadClass("net.minecraft.class_2350$class_2351");
        Object waterlogged = property.getConstructor(String.class).newInstance("waterlogged");
        if (block.equals(LIGHT)) {
            Object model = enumProperty.getConstructor(String.class).newInstance("model"), facing = directionProperty.getConstructor(String.class).newInstance("facing");
            PfmPerformanceProbe.set(type, null, "MODEL", model);
            PfmPerformanceProbe.set(type, null, "FACING", facing);
            PfmPerformanceProbe.set(type, null, "SHAPE_COMMON", shape.getConstructor(String.class).newInstance("common"));
            Map<Object, Map<Object, Object>> table = new HashMap<>();
            List<Object> directions = new ArrayList<>();
            for (String name : List.of("north", "south", "east", "west")) directions.add(direction.getConstructor(String.class).newInstance(name));
            for (String models : List.of("one", "two", "three", "five")) {
                Map<Object, Object> byDirection = new HashMap<>();
                for (Object d : directions) byDirection.put(d, shape.getConstructor(String.class).newInstance(models + "_" + d));
                table.put(models, byDirection);
                for (Object d : directions)
                    for (boolean water : new boolean[]{false, true}) {
                        Map<Object, Object> values = new LinkedHashMap<>();
                        values.put(model, models);
                        values.put(facing, d);
                        values.put(waterlogged, water);
                        states.add(values);
                    }
            }
            PfmPerformanceProbe.set(type, null, "shapes", table);
        } else if (block.equals(POST)) {
            Object axisProperty = enumProperty.getConstructor(String.class).newInstance("axis");
            PfmPerformanceProbe.set(type, null, "AXIS", axisProperty);
            List<Object> sides = new ArrayList<>();
            for (String name : List.of("NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN", "EXTEND_BOTTOM")) {
                Object side = booleanProperty.getConstructor(String.class).newInstance(name.toLowerCase(Locale.ROOT));
                PfmPerformanceProbe.set(type, null, name, side);
                sides.add(side);
            }
            for (String name : List.of("BASE", "NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN", "EXTEND_DOWN"))
                PfmPerformanceProbe.set(type, null, "SHAPE_" + name, shape.getConstructor(String.class).newInstance(name.toLowerCase(Locale.ROOT)));
            for (String axisName : List.of("field_11048", "field_11052", "field_11051"))
                for (int bits = 0; bits < 1 << sides.size(); bits++) {
                    Map<Object, Object> values = new LinkedHashMap<>();
                    values.put(axisProperty, axis.getField(axisName).get(null));
                    for (int i = 0; i < sides.size(); i++) values.put(sides.get(i), (bits & 1 << i) != 0);
                    states.add(values);
                }
        } else {
            Object quality = enumProperty.getConstructor(String.class).newInstance("quality");
            for (String name : List.of("fresh", "used", "old")) states.add(new LinkedHashMap<>(Map.of(quality, name)));
        }
        return states;
    }

    // ---------------------------------------------------------------- sign texture reset

    @SuppressWarnings("unchecked")
    private static void checkResetRecipients(Path output, Path fixtures, Map<String, byte[]> official, Map<String, byte[]> fixed) throws Exception {
        for (String level : List.of("server", "other", "client")) {
            List<String> original = runReset(output.resolve("reset/original"), fixtures, official, official.get(SIGN_BE), level);
            List<String> patched = runReset(output.resolve("reset/fixed"), fixtures, official, fixed.get(SIGN_BE), level);
            List<String> expected = new ArrayList<>();
            for (String entry : original) {
                if (level.equals("server") && entry.equals("players")) { expected.add("chunkPlayers 0,1 false"); continue; }
                // Players outside the chunk (B, far away; D, whose chunk is still queued) no longer receive the reset.
                if (level.equals("server") && (entry.startsWith("send B ") || entry.startsWith("send D "))) continue;
                expected.add(entry);
            }
            if (level.equals("server") && (original.stream().filter(e -> e.startsWith("send ")).count() != 4 || expected.equals(original)))
                throw new AssertionError("Reset scenario did not reach all level players: " + original);
            if (!patched.equals(expected))
                throw new AssertionError("Sign reset recipients changed in " + level + " level:\noriginal " + original + "\nfixed    " + patched);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> runReset(Path classes, Path fixtures, Map<String, byte[]> official, byte[] bytes, String levelType) throws Exception {
        write(classes, fixtures, SYNCED, PfmPerformanceProbe.isolate(official.get(SYNCED), f -> false,
            m -> Set.of("notifyUpdate", "sendData").contains(m.name), null, List.of()));
        write(classes, fixtures, SIGN_BE, PfmPerformanceProbe.isolate(bytes, "textureId"::equals,
            m -> Set.of("setAndResetTexture", "setTextureId").contains(m.name) || m.name.startsWith("lambda$setAndResetTexture")
                || PfmPerformanceProbe.generated(m), SYNCED, List.of()));
        try (URLClassLoader loader = loader(classes)) {
            Class<?> type = loader.loadClass(SIGN_BE.replace('/', '.')), levelClass = loader.loadClass("net.minecraft.class_1937");
            Class<?> serverPlayer = loader.loadClass("net.minecraft.class_3222"), pos = loader.loadClass("net.minecraft.class_2338");
            List<String> log = (List<String>) levelClass.getField("LOG").get(null);
            log.clear();
            Object level = loader.loadClass(levelType.equals("server") ? "net.minecraft.class_3218" : "net.minecraft.class_1937").getConstructor().newInstance();
            levelClass.getField("field_9236").setBoolean(level, levelType.equals("client"));
            Map<String, Object> players = new LinkedHashMap<>();
            for (String name : List.of("A", "B", "C", "D")) players.put(name, serverPlayer.getConstructor(String.class).newInstance(name));
            ((List<Object>) levelClass.getField("players").get(level)).addAll(players.values());
            if (levelType.equals("server")) {
                Object chunkMap = level.getClass().getMethod("method_14178").invoke(level).getClass().getField("field_17254")
                    .get(level.getClass().getMethod("method_14178").invoke(level));
                Object chunk = loader.loadClass("net.minecraft.class_1923").getConstructor(pos).newInstance(pos.getConstructor(int.class, int.class, int.class).newInstance(5, 64, 20));
                ((Map<Object, List<Object>>) chunkMap.getClass().getField("tracking").get(chunkMap)).put(chunk, List.of(players.get("A"), players.get("C")));
            }
            Object entity = type.getConstructor().newInstance();
            Class<?> base = loader.loadClass("net.minecraft.class_2586");
            base.getField("field_11863").set(entity, level);
            base.getField("pos").set(entity, pos.getConstructor(int.class, int.class, int.class).newInstance(5, 64, 20));
            Object texture = loader.loadClass(TC.replace('/', '.') + "data.NamedTrafficSignTextureReference").getConstructor().newInstance();
            type.getMethod("setAndResetTexture", texture.getClass()).invoke(entity, texture);
            return new ArrayList<>(log);
        }
    }

    // ---------------------------------------------------------------- bulb model lookup

    /**
     * Runs the real TrafficLightTextureManager/TrafficLightBulbModel classes, including their static initializer,
     * for every icon/color pair twice with fresh keys. Draw calls, lights and failures must match exactly; the
     * transformed code searches the model list once per pair.
     */
    @SuppressWarnings("unchecked")
    private static int checkBulbLookups(Path output, Path fixtures, Map<String, byte[]> official, Map<String, byte[]> fixed) throws Exception {
        Map<Boolean, List<String>> logs = new HashMap<>();
        Map<Boolean, Integer> searches = new HashMap<>();
        int calls = 0;
        Set<String> pairs = new HashSet<>();
        for (boolean patched : new boolean[]{false, true}) {
            Path classes = output.resolve("bulbs/" + (patched ? "fixed" : "original"));
            for (String name : List.of(TEXTURES, BULB, KEY, TEXTURES + "$1", TC + "block/data/TrafficLightIcon", TC + "block/data/TrafficLightColor",
                    TC + "block/data/TrafficLightType", TC + "block/data/IIconEnum", DL + "core/ITranslatableEnum")) {
                byte[] bytes = patched && fixed.containsKey(name) ? fixed.get(name) : official.get(name);
                if (name.equals(TEXTURES)) bytes = unfinal(bytes, "models");
                write(classes, fixtures, name, bytes);
            }
            try (URLClassLoader loader = loader(classes)) {
                Class<?> manager = loader.loadClass(TEXTURES.replace('/', '.')), key = loader.loadClass(KEY.replace('/', '.'));
                Class<?> icons = loader.loadClass(TC.replace('/', '.') + "block.data.TrafficLightIcon"), colors = loader.loadClass(TC.replace('/', '.') + "block.data.TrafficLightColor");
                Class<?> graphicsType = loader.loadClass(DL.replace('/', '.') + "client.ber.BERGraphics"), entityType = loader.loadClass("net.minecraft.class_2586");
                Class<?> counting = loader.loadClass("CountingList");
                Field models = manager.getDeclaredField("models");
                models.setAccessible(true);
                Object list = counting.getConstructor(Collection.class).newInstance(models.get(null));
                models.set(null, list);
                List<String> log = (List<String>) loader.loadClass("net.minecraft.class_1937").getField("LOG").get(null);
                log.clear();
                Method render = manager.getMethod("render", graphicsType, entityType, key, int.class);
                Object graphics = graphicsType.getConstructor().newInstance();
                Object[] iconValues = (Object[]) icons.getMethod("values").invoke(null), colorValues = (Object[]) colors.getMethod("values").invoke(null);
                pairs.clear();
                calls = 0;
                for (int round = 0; round < 2; round++) {
                    for (Object icon : iconValues) {
                        for (Object color : colorValues) {
                            Object instance = key.getConstructor(icons, colors).newInstance(icon, color);
                            // Keys normalize icons that do not apply to the color, so count distinct resulting pairs.
                            pairs.add(key.getMethod("getIcon").invoke(instance) + "/" + key.getMethod("getColor").invoke(instance));
                            for (int light : new int[]{0x00F000F0, 7}) {
                                log.add("render " + icon + " " + color + " " + light);
                                calls++;
                                try {
                                    render.invoke(null, graphics, null, instance, light);
                                } catch (InvocationTargetException e) {
                                    log.add("throws " + e.getCause().getClass().getName());
                                }
                            }
                        }
                    }
                }
                logs.put(patched, new ArrayList<>(log));
                searches.put(patched, counting.getField("streams").getInt(list));
            }
        }
        if (!logs.get(false).equals(logs.get(true)))
            throw new AssertionError("Bulb drawing changed:\noriginal " + logs.get(false) + "\nfixed    " + logs.get(true));
        if (searches.get(false) != calls || searches.get(true) != pairs.size())
            throw new AssertionError("Unexpected model searches: original " + searches.get(false) + "/" + calls + ", fixed " + searches.get(true) + "/" + pairs.size());
        if (logs.get(false).stream().noneMatch(e -> e.startsWith("cube ")))
            throw new AssertionError("No bulb was drawn");
        return calls;
    }

    private static byte[] unfinal(byte[] bytes, String field) {
        ClassNode node = node(bytes);
        for (FieldNode candidate : node.fields) if (candidate.name.equals(field)) candidate.access &= ~Opcodes.ACC_FINAL;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    // ---------------------------------------------------------------- helpers

    private static void readClasses(InputStream input, Map<String, byte[]> classes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/"))
                    classes.put(entry.getName().substring(0, entry.getName().length() - 6), zip.readAllBytes());
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = TrafficCraftPerformanceProbe.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new FileNotFoundException(name);
            return stream.readAllBytes();
        }
    }

    private static ClassNode node(byte[] bytes) {
        return bytes == null ? null : PfmPerformanceProbe.node(bytes);
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
            .orElseThrow(() -> new AssertionError(node.name + "." + name + desc + " not found"));
    }

    private static MethodNode methodNamed(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + " not found"));
    }

    /** Copies the fixtures and the shipped helper classes, then writes the class under test. */
    private static void write(Path classes, Path fixtures, String name, byte[] bytes) throws IOException {
        if (!Files.exists(classes.resolve("net/minecraft/class_2338.class"))) {
            try (var files = Files.walk(fixtures)) {
                for (Path path : files.toList()) {
                    if (!Files.isRegularFile(path)) continue;
                    Path destination = classes.resolve(fixtures.relativize(path));
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (String helper : HELPER_CLASSES) {
                Path destination = classes.resolve(HELPERS + helper + ".class");
                Files.createDirectories(destination.getParent());
                Files.write(destination, resource(HELPERS + helper + ".class"));
            }
        }
        Path destination = classes.resolve(name + ".class");
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
    }

    /**
     * Helpers of the mod JAR are loaded next to the fixtures so they link against them. Everything else, including
     * Mixin, MixinExtras and the invoker interface, comes from the probe class path.
     */
    private static URLClassLoader loader(Path classes) throws MalformedURLException {
        String helpers = HELPERS.replace('/', '.');
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, TrafficCraftPerformanceProbe.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(helpers)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
    }

    // ---------------------------------------------------------------- fixtures

    private static void compileFixtures(Path classes) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        String mc = "package net.minecraft; ";
        sources.put("CountingList", """
            public class CountingList extends java.util.ArrayList<Object> {
                public int streams;
                public CountingList(java.util.Collection<?> values) { super(values); }
                @Override public java.util.stream.Stream<Object> stream() { streams++; return super.stream(); }
            }
            """);
        sources.put("net.minecraft.class_2338", mc + """
            public class class_2338 {
                public final int x, y, z;
                public class_2338(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
                public String toString() { return x + "," + y + "," + z; }
            }
            """);
        sources.put("net.minecraft.class_1923", mc + """
            public class class_1923 {
                public final int x, z;
                public class_1923(class_2338 pos) { x = pos.x >> 4; z = pos.z >> 4; }
                public boolean equals(Object o) { return o instanceof class_1923 p && p.x == x && p.z == z; }
                public int hashCode() { return x * 31 + z; }
                public String toString() { return x + "," + z; }
            }
            """);
        sources.put("net.minecraft.class_1657", mc + """
            public class class_1657 {
                public final String name;
                public class_1657(String name) { this.name = name; }
                public String toString() { return name; }
            }
            """);
        sources.put("net.minecraft.class_3222", mc + "public class class_3222 extends class_1657 { public class_3222(String name) { super(name); } }");
        sources.put("net.minecraft.class_1937", mc + """
            public class class_1937 {
                public static final java.util.List<String> LOG = new java.util.ArrayList<>();
                public boolean field_9236;
                public final java.util.List<class_1657> players = new java.util.ArrayList<>();
                public java.util.List<class_1657> method_18456() { LOG.add("players"); return players; }
            }
            """);
        sources.put("net.minecraft.class_3218", mc + """
            public class class_3218 extends class_1937 {
                private final class_3215 chunks = new class_3215();
                public class_3215 method_14178() { return chunks; }
            }
            """);
        sources.put("net.minecraft.class_3215", mc + """
            public class class_3215 {
                public final class_3898 field_17254 = new class_3898();
                public void method_14128(class_2338 pos) { class_1937.LOG.add("blockChanged " + pos); }
            }
            """);
        sources.put("net.minecraft.class_3898", mc + """
            public class class_3898 {
                public final java.util.Map<class_1923, java.util.List<Object>> tracking = new java.util.HashMap<>();
                public java.util.List<Object> method_17210(class_1923 pos, boolean edge) {
                    class_1937.LOG.add("chunkPlayers " + pos + " " + edge);
                    return java.util.List.copyOf(tracking.getOrDefault(pos, java.util.List.of()));
                }
            }
            """);
        sources.put("net.minecraft.class_2586", mc + """
            public abstract class class_2586 {
                public class_1937 field_11863;
                public class_2338 pos;
                public class_2338 method_11016() { return pos; }
                public void method_5431() { class_1937.LOG.add("setChanged"); }
            }
            """);
        sources.put("net.minecraft.class_1922", mc + "public interface class_1922 {}");
        sources.put("net.minecraft.class_3726", mc + "public interface class_3726 {}");
        sources.put("net.minecraft.class_265", mc + """
            public class class_265 {
                private final String shape;
                public class_265(String shape) { this.shape = shape; }
                public String toString() { return shape; }
            }
            """);
        sources.put("net.minecraft.class_259", mc + """
            public class class_259 {
                public static int built;
                public static class_265 method_1084(class_265 a, class_265 b) { built++; return new class_265("or(" + a + "," + b + ")"); }
                public static class_265 method_17786(class_265 first, class_265... others) {
                    class_265 shape = first;
                    for (class_265 other : others) shape = method_1084(shape, other);
                    return shape;
                }
            }
            """);
        sources.put("net.minecraft.class_2248", mc + """
            public class class_2248 {
                public static class_265 method_9541(double x1, double y1, double z1, double x2, double y2, double z2) {
                    class_259.built++;
                    return new class_265("box(" + x1 + "," + y1 + "," + z1 + "," + x2 + "," + y2 + "," + z2 + ")");
                }
            }
            """);
        sources.put("net.minecraft.class_2769", mc + """
            public class class_2769 {
                private final String name;
                public class_2769(String name) { this.name = name; }
                public String toString() { return name; }
            }
            """);
        sources.put("net.minecraft.class_2754", mc + "public class class_2754 extends class_2769 { public class_2754(String name) { super(name); } }");
        sources.put("net.minecraft.class_2753", mc + "public class class_2753 extends class_2754 { public class_2753(String name) { super(name); } }");
        sources.put("net.minecraft.class_2746", mc + "public class class_2746 extends class_2769 { public class_2746(String name) { super(name); } }");
        sources.put("net.minecraft.class_2350", mc + """
            public final class class_2350 implements Comparable<Object> {
                private final String name;
                public class_2350(String name) { this.name = name; }
                public String toString() { return name; }
                public int compareTo(Object other) { return name.compareTo(other.toString()); }
                public static final class class_2351 implements Comparable<Object> {
                    public static final class_2351 field_11048 = new class_2351("x"), field_11052 = new class_2351("y"), field_11051 = new class_2351("z");
                    private final String name;
                    private class_2351(String name) { this.name = name; }
                    public String toString() { return name; }
                    public int compareTo(Object other) { return name.compareTo(other.toString()); }
                }
            }
            """);
        sources.put("net.minecraft.class_2680", mc + """
            public class class_2680 {
                private final class_2248 block;
                private final java.util.Map<Object, Object> values;
                public class_2680(class_2248 block, java.util.Map<Object, Object> values) { this.block = block; this.values = values; }
                public class_2248 method_26204() { return block; }
                public Comparable<?> method_11654(class_2769 property) {
                    if (!values.containsKey(property)) throw new IllegalArgumentException("No property " + property);
                    return (Comparable<?>) values.get(property);
                }
                public String toString() { return values.toString(); }
            }
            """);
        sources.put("net.minecraft.class_2960", mc + """
            public class class_2960 {
                private final String id;
                public class_2960(String namespace, String path) { id = namespace + ":" + path; }
                public String toString() { return id; }
            }
            """);
        sources.put("net.minecraft.class_3542", mc + "public interface class_3542 { String method_15434(); }");
        sources.put("net.minecraft.class_4587", mc + """
            public class class_4587 {
                public void method_22903() { class_1937.LOG.add("push"); }
                public void method_22909() { class_1937.LOG.add("pop"); }
                public void method_46416(float x, float y, float z) { class_1937.LOG.add("translate " + x + " " + y + " " + z); }
            }
            """);
        String dl = "package de.mrjulsen.mcdragonlib.client.ber; ";
        sources.put("de.mrjulsen.mcdragonlib.client.ber.RenderGraphics", dl + """
            public class RenderGraphics {
                private final net.minecraft.class_4587 pose = new net.minecraft.class_4587();
                public net.minecraft.class_4587 poseStack() { return pose; }
            }
            """);
        sources.put("de.mrjulsen.mcdragonlib.client.ber.BERGraphics", dl + "public class BERGraphics<B extends net.minecraft.class_2586> extends RenderGraphics {}");
        sources.put("de.mrjulsen.mcdragonlib.client.ber.BERCube", dl + """
            public class BERCube {
                private static int created;
                private final String name;
                public BERCube(float width, float height, float depth) { name = "#" + created++ + " empty"; }
                private BERCube(net.minecraft.class_2960 texture) { name = "#" + created++ + " " + texture; }
                public static BERCube cube(net.minecraft.class_2960 texture, float width, float height, float depth,
                        java.util.function.Predicate<?> faces, java.util.function.Function<?, ?> uv) { return new BERCube(texture); }
                public void setLight(int light) { net.minecraft.class_1937.LOG.add("light " + name + " " + light); }
                public void render(RenderGraphics graphics) { net.minecraft.class_1937.LOG.add("cube " + name); }
            }
            """);
        sources.put("de.mrjulsen.mcdragonlib.client.render.Sprite", "package de.mrjulsen.mcdragonlib.client.render; public class Sprite {}");
        // Named by the bulb models' face-UV lambda, which the fixture cube never calls.
        sources.put("de.mrjulsen.mcdragonlib.data.Pair", """
            package de.mrjulsen.mcdragonlib.data;
            public class Pair<A, B> { public static <A, B> Pair<A, B> of(A first, B second) { throw new AssertionError(); } }
            """);
        sources.put("de.mrjulsen.trafficcraft.TrafficCraft", """
            package de.mrjulsen.trafficcraft;
            public class TrafficCraft {
                private static final dev.architectury.networking.NetworkChannel NET = new dev.architectury.networking.NetworkChannel();
                public static dev.architectury.networking.NetworkChannel net() { return NET; }
            }
            """);
        sources.put("dev.architectury.networking.NetworkChannel", """
            package dev.architectury.networking;
            public class NetworkChannel {
                public void sendToPlayer(net.minecraft.class_3222 player, Object packet) { net.minecraft.class_1937.LOG.add("send " + player + " " + packet); }
            }
            """);
        sources.put("de.mrjulsen.trafficcraft.network.packets.stc.TrafficSignTextureResetPacket", """
            package de.mrjulsen.trafficcraft.network.packets.stc;
            public class TrafficSignTextureResetPacket {
                private final net.minecraft.class_2338 pos;
                public TrafficSignTextureResetPacket(net.minecraft.class_2338 pos) { this.pos = pos; }
                public String toString() { return "reset " + pos; }
            }
            """);
        sources.put("de.mrjulsen.trafficcraft.data.NamedTrafficSignTextureReference", """
            package de.mrjulsen.trafficcraft.data;
            public class NamedTrafficSignTextureReference { public String getTextureId() { return "texture"; } }
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
