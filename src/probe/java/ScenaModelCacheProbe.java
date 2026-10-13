/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import com.google.gson.*;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import team.minefed.mods.clientcompat.scena.ScenaAbsentLoaderCache;

/** Applies the shipped redirect to official Scena bytecode and executes its original resource-load method. */
public final class ScenaModelCacheProbe {
    private static final String TARGET = "com/communi/suggestu/scena/fabric/platform/client/rendering/model/loader/FabricPlatformModelLoaderPlatformDelegate";
    private static final String SHA256 = "8a79d7485fccd67beb50df086ba66827c0218a67565efca1c753a37dbee152b5";
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: ScenaModelCacheProbe <official Scena JAR> <output> [mod model corpus directory]");
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        check(SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))), "official Scena 1.0.141 artifact");
        JsonObject config = PfmPerformanceProbe.json("minefed-scena-model-cache.mixins.json");
        check(config.get("required").getAsBoolean() && config.getAsJsonArray("client").size() == 1
            && config.getAsJsonArray("client").get(0).getAsString().equals("ScenaModelLoaderMixin"), "client-only shipped cache Mixin configuration");
        boolean registered = false;
        for (JsonElement entry : PfmPerformanceProbe.json("fabric.mod.json").getAsJsonArray("mixins")) {
            if (entry.isJsonObject() && entry.getAsJsonObject().get("config").getAsString().equals("minefed-scena-model-cache.mixins.json"))
                registered = entry.getAsJsonObject().get("environment").getAsString().equals("client");
        }
        check(registered, "Scena Mixin registered in client environment");
        Map<String, byte[]> original = new HashMap<>();
        try (JarFile zip = new JarFile(jar.toFile())) {
            for (JarEntry e : Collections.list(zip.entries())) if (e.getName().endsWith(".class"))
                original.put(e.getName().substring(0, e.getName().length() - 6), zip.getInputStream(e).readAllBytes());
        }
        fixtures(output.resolve("fixtures"));
        MixinCompatProbe.ProbeService.officialClasses = original;
        MixinCompatProbe.ProbeService.fixtureClasses = output.resolve("fixtures");
        System.setProperty("mixin.service", MixinCompatProbe.ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("minefed-scena-model-cache.mixins.json");
        var service = (MixinCompatProbe.ProbeService) MixinService.getService();
        var transformer = service.factory().createTransformer();
        for (boolean patched : new boolean[]{false, true}) {
            Path classes = output.resolve(patched ? "fixed" : "original");
            fixtures(classes);
            byte[] bytes = original.get(TARGET);
            if (patched) {
                byte[] transformed = transformer.transformClassBytes(TARGET.replace('/', '.'), TARGET.replace('/', '.'), bytes);
                check(!Arrays.equals(bytes, transformed), "shipped redirect applied to official bytecode");
                bytes = transformed;
            }
            Path file = classes.resolve(TARGET + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, isolate(bytes));
        }
        helperCases();
        try (URLClassLoader before = loader(output.resolve("original")); URLClassLoader after = loader(output.resolve("fixed"))) {
            officialCases(before, after);
        }
        if (args.length > 2) benchmark(Path.of(args[2]));
        System.out.println("PASS: " + checks + " Scena cache checks; official loadModelResource, three provider calls, input changes, custom loaders, malformed/nonobject input, prefix fallback, I/O exceptions, ownership and cache bounds verified.");
    }

    private static void helperCases() throws Exception {
        Gson gson = new Gson();
        ScenaAbsentLoaderCache cache = new ScenaAbsentLoaderCache(4, 2048);
        String plain = "{\"parent\":\"block/cube\",\"textures\":{\"all\":\"block/stone\"}}";
        JsonElement first = cache.readElement(gson, new StringReader(plain));
        first.getAsJsonObject().addProperty("loader", "mutated by caller");
        JsonElement hit = cache.readElement(gson, new StringReader(plain));
        JsonElement secondHit = cache.readElement(gson, new StringReader(plain));
        check(hit != secondHit && !hit.getAsJsonObject().has("loader"), "fresh mutable object on every negative hit");
        check(cache.statistics().hits() == 2, "two repeated negative checks cached");
        check(!cache.readElement(new GsonBuilder().serializeNulls().create(), new StringReader(plain)).getAsJsonObject().has("loader")
            && cache.statistics().hits() == 3, "independent standard Gson instances share equivalent tree semantics");
        nonDefaultAdapterCases(cache, plain);
        Gson lenient = new GsonBuilder().setLenient().create();
        long policyHits = cache.statistics().hits();
        check(result(() -> lenient.fromJson(new StringReader(plain), JsonElement.class))
            .equals(result(() -> cache.readElement(lenient, new StringReader(plain)))), "lenient Gson preserves full object through original fallback");
        check(cache.statistics().hits() == policyHits, "lenient policy bypasses previously populated default cache");
        for (String tail : List.of(" // comment", " /* comment */", " # comment", "\n", "\n[]")) {
            String document = plain + tail;
            check(result(() -> lenient.fromJson(new StringReader(document), JsonElement.class))
                .equals(result(() -> cache.readElement(lenient, new StringReader(document)))), "lenient trailing input policy retained");
            check(result(() -> gson.fromJson(new StringReader(document), JsonElement.class))
                .equals(result(() -> cache.readElement(gson, new StringReader(document)))), "lenient result cannot pollute default parsing");
        }
        for (String document : List.of("null", "[]", "42", "\"text\"", "", " ", "{\"loader\":null}", "{\"loader\":\"test:custom\"}", "{\"loa\\u0064er\":3}", "{\"loader\":false,\"loader\":true}", "{\"a\":1} trailing", "{\"a\":", "}", "/*comment*/ {\"loader\": 1}")) {
            String expected = result(() -> gson.fromJson(new StringReader(document), JsonElement.class));
            String actual = result(() -> cache.readElement(gson, new StringReader(document)));
            check(expected.equals(actual), "original Gson semantics: " + document);
        }
        long misses = cache.statistics().misses();
        cache.readElement(gson, new StringReader("{\"text\":\"Aa\"}"));
        cache.readElement(gson, new StringReader("{\"text\":\"BB\"}"));
        check(cache.statistics().misses() == misses + 2, "full string equality, including Java hash collisions");
        String large = "{\"text\":\"" + "a".repeat(1_100_000) + "\",\"loader\":\"test:late\"}";
        ScenaAbsentLoaderCache fullBudget = new ScenaAbsentLoaderCache(256, 2 * 1024 * 1024);
        check(result(() -> gson.fromJson(new StringReader(large), JsonElement.class))
                .equals(result(() -> fullBudget.readElement(gson, new StringReader(large)))), "over-2MiB input replays all consumed text and late loader");
        check(fullBudget.statistics().entries() == 0, "oversized input not retained");
        for (String prefix : List.of("{\"x\":", "}", "{\"x\":1}", "")) {
            check(result(() -> gson.fromJson(new ThrowAfterPrefix(prefix), JsonElement.class))
                    .equals(result(() -> cache.readElement(gson, new ThrowAfterPrefix(prefix)))), "Gson preserves I/O/syntax failure after prefix");
        }
        TrackingReader owned = new TrackingReader(plain);
        cache.readElement(gson, owned);
        check(!owned.closed, "caller still owns reader");
        for (int i = 0; i < 100; i++) cache.readElement(gson, new StringReader("{\"value\":" + i + "}"));
        check(cache.statistics().entries() <= 4 && cache.statistics().retainedBytes() <= 2048, "entry and byte budgets");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<Void>>();
            for (int thread = 0; thread < 4; thread++) work.add(() -> {
                for (int i = 0; i < 100; i++) {
                    JsonElement e = cache.readElement(gson, new StringReader("{\"threadValue\":" + (i % 9) + "}"));
                    if (!e.isJsonObject() || e.getAsJsonObject().has("loader")) throw new AssertionError("invalid concurrent result");
                }
                return null;
            });
            for (var future : pool.invokeAll(work)) future.get();
        } finally { pool.shutdownNow(); }
        check(cache.statistics().entries() <= 4 && cache.statistics().retainedBytes() <= 2048, "parallel eviction remains bounded");
        check("value".equals(ScenaAbsentLoaderCache.read(gson, new StringReader("\"value\""), String.class)), "non-JsonElement call stays original");
    }

    @SuppressWarnings("unchecked")
    private static void nonDefaultAdapterCases(ScenaAbsentLoaderCache cache, String plain) throws Exception {
        TypeAdapter<JsonElement> custom = new TypeAdapter<>() {
            @Override public JsonElement read(JsonReader reader) { throw new JsonParseException("custom tree adapter failure"); }
            @Override public void write(JsonWriter writer, JsonElement value) { throw new UnsupportedOperationException(); }
        };
        Field adapters = Gson.class.getDeclaredField("typeTokenCache");
        adapters.setAccessible(true);
        for (boolean sameWrapper : new boolean[]{false, true}) {
            Gson customGson = new Gson();
            TypeAdapter<JsonElement> installed = sameWrapper
                ? TypeAdapters.newTypeHierarchyFactory(JsonElement.class, custom).create(customGson, TypeToken.get(JsonElement.class))
                : custom;
            ((Map<TypeToken<?>, TypeAdapter<?>>) adapters.get(customGson)).put(TypeToken.get(JsonElement.class), installed);
            long hits = cache.statistics().hits();
            check(result(() -> customGson.fromJson(new StringReader(plain), JsonElement.class))
                .equals(result(() -> cache.readElement(customGson, new StringReader(plain)))), "custom tree adapter preserves exceptions, same wrapper=" + sameWrapper);
            check(cache.statistics().hits() == hits, "custom adapter bypasses negative cache, same wrapper=" + sameWrapper);
        }
    }

    private static void officialCases(ClassLoader before, ClassLoader after) throws Exception {
        for (String json : List.of("{\"parent\":\"test:ordinary-provider-check\"}", "{\"loader\":\"test:custom\",\"value\":7}", "{\"loader\":null}", "{\"loader\":\"throw\"}", "[1,2]", "null", "", "{broken", "{\"loader\":1}")) {
            String original = officialResult(before, json, false);
            String patched = officialResult(after, json, false);
            check(original.equals(patched), "official provider result/exception preserved: " + json);
        }
        String json = "{\"parent\":\"test:three-registered-provider-checks\",\"elements\":[]}";
        var start = ScenaAbsentLoaderCache.sharedStatistics();
        check(officialResult(after, json, true).equals("null|null|null|opens=3|closes=6"), "three official provider invocations preserve resource ownership");
        var end = ScenaAbsentLoaderCache.sharedStatistics();
        check(end.hits() - start.hits() == 2 && end.misses() - start.misses() == 1, "three providers perform one AST parse and two negative cache hits");
        check(officialResult(before, json, true).equals(officialResult(after, json, true)), "three-provider results equal original");
        // The resource identifier is unchanged; a changed loader property must still reach the custom loader.
        String edited = "{\"loader\":\"test:edited-resource\",\"elements\":[]}";
        check(officialResult(before, edited, false).equals(officialResult(after, edited, false)), "same resource path changed content is not stale");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String officialResult(ClassLoader loader, String json, boolean three) throws Exception {
        Class<?> modelType = loader.loadClass("net.minecraft.class_1100");
        JsonDeserializer<?> modelAdapter = (element, type, context) -> {
            JsonElement tag = element.getAsJsonObject().get("loader");
            if (!tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString()) throw new JsonParseException("invalid custom loader");
            if (tag.getAsString().equals("throw")) throw new IllegalArgumentException("custom loader failure");
            return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{modelType}, (p, method, a) -> element.toString());
        };
        Class<?> minecraft = loader.loadClass("net.minecraft.class_310");
        minecraft.getMethod("reset", String.class).invoke(null, json);
        Class<?> idType = loader.loadClass("net.minecraft.class_2960");
        Object id = idType.getConstructor(String.class, String.class).newInstance("test", "unchanged-path");
        Class<?> target = loader.loadClass(TARGET.replace('/', '.'));
        Method load = target.getMethod("loadModelResource", idType, loader.loadClass("net.fabricmc.fabric.api.client.model.ModelProviderContext"));
        List<String> results = new ArrayList<>();
        for (int i = 0; i < (three ? 3 : 1); i++) {
            // The official constructor creates a distinct Gson for every registered provider.
            Gson gson = new GsonBuilder().registerTypeAdapter(modelType, modelAdapter).create();
            Object provider = target.getConstructor(Gson.class).newInstance(gson);
            results.add(result(() -> {
                try { return load.invoke(provider, id, null); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            }));
        }
        return String.join("|", results) + "|opens=" + minecraft.getField("opens").get(null) + "|closes=" + minecraft.getField("closes").get(null);
    }

    private static void benchmark(Path mods) throws Exception {
        List<String> documents = new ArrayList<>();
        Random sample = new Random(20261013);
        int seen = 0;
        try (var paths = Files.list(mods)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) try (JarFile jar = new JarFile(path.toFile())) {
                for (JarEntry e : Collections.list(jar.entries())) if (e.getName().startsWith("assets/") && e.getName().contains("/models/") && e.getName().endsWith(".json")) {
                    int index = sample.nextInt(++seen);
                    if (documents.size() < 4096) documents.add(new String(jar.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    else if (index < 4096) documents.set(index, new String(jar.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        Gson[] providers = {new Gson(), new Gson(), new Gson()};
        double[] baseline = new double[5], candidate = new double[5];
        int expectedWithLoader = -1;
        for (int iteration = -2; iteration < 5; iteration++) {
            for (int turn = 0; turn < 2; turn++) {
                boolean patched = ((iteration + turn) & 1) != 0;
                ScenaAbsentLoaderCache cache = new ScenaAbsentLoaderCache(256, 2 * 1024 * 1024);
                long start = System.nanoTime();
                int withLoader = 0;
                for (String document : documents) for (int provider = 0; provider < 3; provider++) {
                    JsonElement e = patched ? cache.readElement(providers[provider], new StringReader(document)) : providers[provider].fromJson(new StringReader(document), JsonElement.class);
                    if (e != null && e.isJsonObject() && e.getAsJsonObject().has("loader")) withLoader++;
                }
                long nanos = System.nanoTime() - start;
                if (expectedWithLoader < 0) expectedWithLoader = withLoader;
                check(withLoader == expectedWithLoader, "corpus loader decisions equal original");
                if (iteration >= 0) (patched ? candidate : baseline)[iteration] = nanos / 1e6;
            }
        }
        Arrays.sort(baseline); Arrays.sort(candidate);
        System.out.printf("Scena parse kernel: 4096-reservoir sample of %d raw mod model JSON entries; three providers; 5-run warm medians %.3f -> %.3f ms; excludes ZIP I/O, custom model deserialization and model baking.%n", seen, baseline[2], candidate[2]);
    }

    private interface Attempt { Object run() throws Throwable; }
    private static String result(Attempt attempt) {
        try { return String.valueOf(attempt.run()); }
        catch (Throwable error) { return error.getClass().getName() + ":" + error.getMessage(); }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static URLClassLoader loader(Path path) throws Exception { return new URLClassLoader(new URL[]{path.toUri().toURL()}, ScenaModelCacheProbe.class.getClassLoader()); }

    private static byte[] isolate(byte[] input) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public void visit(int v, int a, String n, String s, String p, String[] i) { super.visit(v, a, n, null, p, new String[0]); }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) { return n.equals("gson") ? super.visitField(a, n, d, null, v) : null; }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return n.equals("loadModelResource") || n.contains("minefed") ? super.visitMethod(a, n, d, null, e) : null;
            }
            @Override public void visitEnd() {
                MethodVisitor m = super.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lcom/google/gson/Gson;)V", null, null);
                m.visitCode(); m.visitVarInsn(Opcodes.ALOAD, 0); m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                m.visitVarInsn(Opcodes.ALOAD, 0); m.visitVarInsn(Opcodes.ALOAD, 1); m.visitFieldInsn(Opcodes.PUTFIELD, TARGET, "gson", "Lcom/google/gson/Gson;");
                m.visitInsn(Opcodes.RETURN); m.visitMaxs(2, 2); m.visitEnd(); super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void fixtures(Path output) throws IOException {
        Map<String, String> sources = Map.of(
            "net.minecraft.class_2960", "package net.minecraft; public record class_2960(String namespace,String path) { public String method_12836(){return namespace;} public String method_12832(){return path;} }",
            "net.minecraft.class_3300", "package net.minecraft; public interface class_3300 { java.util.Optional<class_3298> method_14486(class_2960 id); }",
            "net.minecraft.class_3298", "package net.minecraft; public class class_3298 { public java.io.InputStream method_14482() throws java.io.IOException {class_310.opens++; return new java.io.ByteArrayInputStream(class_310.content.getBytes(java.nio.charset.Charset.defaultCharset())) {public void close() {class_310.closes++;}};} }",
            "net.minecraft.class_310", "package net.minecraft; public class class_310 {public static int opens,closes; public static String content; public static void reset(String s){content=s;opens=closes=0;} public static class_310 method_1551(){return new class_310();} public class_3300 method_1478(){return id->java.util.Optional.of(new class_3298());}}",
            "net.minecraft.class_1100", "package net.minecraft; public interface class_1100 {}",
            "net.fabricmc.fabric.api.client.model.ModelProviderContext", "package net.fabricmc.fabric.api.client.model; public interface ModelProviderContext {}",
            "net.fabricmc.fabric.api.client.model.ModelResourceProvider", "package net.fabricmc.fabric.api.client.model; public interface ModelResourceProvider {}",
            "net.fabricmc.fabric.api.client.model.ModelProviderException", "package net.fabricmc.fabric.api.client.model; public class ModelProviderException extends RuntimeException {public ModelProviderException(String m,Throwable t){super(m,t);}}"
        );
        Files.createDirectories(output);
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-d", output.toString()));
        for (var source : sources.entrySet()) {
            Path file = output.resolveSibling(output.getFileName() + "-src").resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent()); Files.writeString(file, source.getValue()); args.add(file.toString());
        }
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0) throw new AssertionError("Scena fixture compilation failed");
    }

    private static final class TrackingReader extends StringReader {
        private boolean closed;
        private TrackingReader(String text) { super(text); }
        @Override public void close() { closed = true; super.close(); }
    }
    private static final class ThrowAfterPrefix extends Reader {
        private final StringReader prefix;
        private ThrowAfterPrefix(String text) { prefix = new StringReader(text); }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            int count = prefix.read(chars, offset, length);
            if (count >= 0) return count;
            throw new IOException("probe read failure");
        }
        @Override public void close() {}
    }
}
