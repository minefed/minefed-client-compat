/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */

import team.minefed.mods.clientcompat.atlas.AtlasSpriteOptimizer;
import team.minefed.mods.clientcompat.atlas.AtlasSpriteOptimizer.Result;
import team.minefed.mods.clientcompat.atlas.AtlasSpriteOptimizer.Settings;
import team.minefed.mods.clientcompat.atlas.AtlasSpriteCache;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the block atlas sprite rules on synthetic images and, when a directory of mod JARs is given,
 * on every block atlas texture they contain:
 * <ul>
 * <li>every sprite ends up with sides that are multiples of the mipmap alignment (16 at level 4),</li>
 * <li>results marked exact sample the original texel at every texel centre,</li>
 * <li>animation frame counts are preserved.</li>
 * </ul>
 */
public final class AtlasSpriteProbe {

    private static final String[] ATLAS_DIRECTORIES = {"block/", "item/", "block_/", "0extra_building_block/", "0extra_building_material/"};
    private static final Pattern TEXTURE = Pattern.compile("assets/([^/]+)/textures/(.+)\\.png");

    public static void main(String[] args) throws Exception {
        syntheticChecks();
        cacheChecks();
        if (args.length > 0) {
            scan(new File(args[0]));
        }
        System.out.println("AtlasSpriteProbe: PASSED");
    }

    private static void syntheticChecks() {
        final Settings settings = Settings.defaults();
        final Random random = new Random(1);

        // Exact 8x enlargement is stored at its original size, pixel for pixel
        final int[] base = randomPixels(random, 16 * 16);
        final int[] enlarged = enlarge(base, 16, 16, 8);
        final Result exact = AtlasSpriteOptimizer.optimize("citycraft", 128, 128, 128, 128, (x, y) -> enlarged[y * 128 + x], 4, settings);
        check(exact != null && exact.frameWidth == 16 && exact.frameHeight == 16 && exact.exactOnly, "exact downscale");
        check(Arrays.equals(exact.pixels, base), "exact downscale keeps every texel");

        // A detailed image is kept
        final int[] detailed = randomPixels(random, 128 * 128);
        check(AtlasSpriteOptimizer.optimize("mod", 128, 128, 128, 128, (x, y) -> detailed[y * 128 + x], 4, settings) == null, "detailed image kept");

        // Size cap, and its namespace exclusion
        final int[] large = randomPixels(random, 2048 * 2048);
        final Result capped = AtlasSpriteOptimizer.optimize("citycraft", 2048, 2048, 2048, 2048, (x, y) -> large[y * 2048 + x], 4, settings);
        check(capped != null && capped.frameWidth == 512 && capped.frameHeight == 512 && !capped.exactOnly, "size cap");
        check(AtlasSpriteOptimizer.optimize("msd", 2048, 2048, 2048, 2048, (x, y) -> large[y * 2048 + x], 4, settings) == null, "excluded namespace");
        check(AtlasSpriteOptimizer.optimize("citycraft", 2048, 2048, 2048, 2048, (x, y) -> large[y * 2048 + x], 4,
            new Settings(true, true, 0, Collections.emptySet())) == null, "size cap disabled");

        // Mipmap alignment: nearest enlargement for small factors
        final int[] odd = randomPixels(random, 24 * 24);
        final Result aligned = AtlasSpriteOptimizer.optimize("citycraft", 24, 24, 24, 24, (x, y) -> odd[y * 24 + x], 4, settings);
        check(aligned != null && aligned.frameWidth == 48 && aligned.exactOnly, "24 -> 48");
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 48; x++) {
                check(aligned.get(x, y) == odd[(y / 2) * 24 + x / 2], "nearest enlargement keeps texels");
            }
        }
        // Large factors are resampled to the nearest multiple of 16
        final int[] brush = randomPixels(random, 21 * 21);
        final Result resampled = AtlasSpriteOptimizer.optimize("ptsdeco", 21, 21, 21, 21, (x, y) -> brush[y * 21 + x], 4, settings);
        check(resampled != null && resampled.frameWidth == 16 && !resampled.exactOnly, "21 -> 16");
        final int[] sign = randomPixels(random, 900 * 900);
        final Result signResult = AtlasSpriteOptimizer.optimize("msd", 900, 900, 900, 900, (x, y) -> sign[y * 900 + x], 4, settings);
        check(signResult != null && signResult.frameWidth == 896, "900 -> 896");

        // Animated sprites keep their frame count
        final int[] animated = randomPixels(random, 24 * 96);
        final Result frames = AtlasSpriteOptimizer.optimize("mtr", 24, 96, 24, 24, (x, y) -> animated[y * 24 + x], 4, settings);
        check(frames != null && frames.frameWidth == 48 && frames.imageHeight == 48 * 4 && frames.imageWidth == 48, "animated frames");
        check(frames.get(47, 191) == animated[95 * 24 + 23], "animated texels");

        // Mipmaps disabled: nothing to align
        check(AtlasSpriteOptimizer.optimize("citycraft", 24, 24, 24, 24, (x, y) -> odd[y * 24 + x], 0, settings) == null, "mip level 0");
        System.out.println("AtlasSpriteProbe: synthetic checks passed");
    }

    private static void scan(File modsDirectory) throws Exception {
        final Settings settings = Settings.defaults();
        final Map<String, Object[]> sprites = new TreeMap<>();
        final File[] jars = modsDirectory.listFiles((dir, name) -> name.endsWith(".jar"));
        check(jars != null && jars.length > 0, "no JARs in " + modsDirectory);
        Arrays.sort(jars);
        for (final File jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                for (final ZipEntry entry : Collections.list(zip.entries())) {
                    final Matcher matcher = TEXTURE.matcher(entry.getName());
                    if (!matcher.matches() || Arrays.stream(ATLAS_DIRECTORIES).noneMatch(matcher.group(2)::startsWith)) {
                        continue;
                    }
                    final BufferedImage image;
                    try (InputStream inputStream = zip.getInputStream(entry)) {
                        image = ImageIO.read(inputStream);
                    }
                    if (image == null) {
                        continue;
                    }
                    final ZipEntry meta = zip.getEntry(entry.getName() + ".mcmeta");
                    int frameWidth = image.getWidth();
                    int frameHeight = image.getHeight();
                    if (meta != null) {
                        try (InputStream inputStream = zip.getInputStream(meta)) {
                            final String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                            if (text.contains("\"animation\"")) {
                                final Integer width = number(text, "width");
                                final Integer height = number(text, "height");
                                if (width == null && height == null) {
                                    frameWidth = frameHeight = Math.min(image.getWidth(), image.getHeight());
                                } else {
                                    frameWidth = width == null ? image.getWidth() : width;
                                    frameHeight = height == null ? image.getHeight() : height;
                                }
                            }
                        }
                    }
                    sprites.put(matcher.group(1) + ":" + matcher.group(2), new Object[]{image, frameWidth, frameHeight});
                }
            }
        }
        long before = 0;
        long after = 0;
        int exact = 0;
        int changedPixels = 0;
        int unchanged = 0;
        final Map<String, Integer> byNamespace = new TreeMap<>();
        // Optional list of the final frame sizes, e.g. for an atlas size estimate
        final String dumpPath = System.getProperty("atlas.dump");
        final StringBuilder dump = new StringBuilder();
        final List<Input> inputs = new ArrayList<>();
        for (final Map.Entry<String, Object[]> entry : sprites.entrySet()) {
            final BufferedImage image = (BufferedImage) entry.getValue()[0];
            final int frameWidth = (int) entry.getValue()[1];
            final int frameHeight = (int) entry.getValue()[2];
            final String namespace = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            final int width = image.getWidth();
            final int height = image.getHeight();
            final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            inputs.add(new Input(entry.getKey(), namespace, width, height, frameWidth, frameHeight, pixels));
            final Result result = AtlasSpriteOptimizer.optimize(namespace, width, height, frameWidth, frameHeight, (x, y) -> pixels[y * width + x], 4, settings);
            before += (long) frameWidth * frameHeight;
            dump.append(entry.getKey()).append(' ').append(result == null ? frameWidth : result.frameWidth).append(' ').append(result == null ? frameHeight : result.frameHeight).append('\n');
            if (result == null) {
                unchanged++;
                after += (long) frameWidth * frameHeight;
                if (width % frameWidth == 0 && height % frameHeight == 0) {
                    check(Integer.lowestOneBit(frameWidth) >= 16 && Integer.lowestOneBit(frameHeight) >= 16, "unchanged but unaligned: " + entry.getKey());
                }
                continue;
            }
            after += (long) result.frameWidth * result.frameHeight;
            byNamespace.merge(namespace, 1, Integer::sum);
            check(Integer.lowestOneBit(result.frameWidth) >= 16 && Integer.lowestOneBit(result.frameHeight) >= 16, "unaligned result: " + entry.getKey());
            check((width / frameWidth) * (height / frameHeight) == (result.imageWidth / result.frameWidth) * (result.imageHeight / result.frameHeight), "frame count: " + entry.getKey());
            if (result.exactOnly) {
                exact++;
                // Every new texel centre maps to the original texel at the same relative position
                for (int y = 0; y < result.imageHeight; y++) {
                    for (int x = 0; x < result.imageWidth; x++) {
                        final int sourceX = (int) ((x + 0.5) * width / result.imageWidth);
                        final int sourceY = (int) ((y + 0.5) * height / result.imageHeight);
                        check(result.get(x, y) == pixels[sourceY * width + sourceX], "exact result differs: " + entry.getKey());
                    }
                }
            } else {
                changedPixels++;
            }
        }
        if (dumpPath != null) {
            java.nio.file.Files.writeString(java.nio.file.Paths.get(dumpPath), dump);
        }
        System.out.printf("AtlasSpriteProbe: %d block atlas sprites, %d unchanged, %d exact, %d resampled; %.1f -> %.1f Mpx; by namespace %s%n",
            sprites.size(), unchanged, exact, changedPixels, before / 1e6, after / 1e6, byNamespace);
        benchmarkCache(inputs, settings);
    }

    private record Input(String id, String namespace, int width, int height, int frameWidth, int frameHeight, int[] pixels) {
        int pixel(int x, int y) { return pixels[y * width + x]; }
        Result original(Settings settings) {
            return AtlasSpriteOptimizer.optimize(namespace, width, height, frameWidth, frameHeight, this::pixel, 4, settings);
        }
        Result cached(AtlasSpriteCache cache, Settings settings) {
            return cache.optimize(id, namespace, width, height, frameWidth, frameHeight, this::pixel, 4, settings);
        }
    }

    private static void cacheChecks() {
        Settings settings = Settings.defaults();
        int[] pixels = enlarge(randomPixels(new Random(91), 16 * 16), 16, 16, 8);
        Input input = new Input("test:image", "test", 128, 128, 128, 128, pixels);
        AtlasSpriteCache cache = new AtlasSpriteCache(32 * 1024 * 1024, 512);
        Result first = input.cached(cache, settings);
        same(first, input.cached(cache, settings), "unchanged content");
        check(cache.hits() == 1, "cache hit");
        // Callers cannot mutate retained results, either at insertion or on a hit.
        first.pixels[0] ^= 1;
        Result hit = input.cached(cache, settings);
        hit.pixels[0] ^= 1;
        same(input.original(settings), input.cached(cache, settings), "result ownership");
        long hits = cache.hits();
        pixels[pixels.length - 1] ^= 1; // Same identity/dimensions; changed last pixel must invalidate.
        same(input.original(settings), input.cached(cache, settings), "content changed");
        check(cache.hits() == hits, "content invalidation");
        pixels[pixels.length - 1] ^= 1;
        same(input.original(settings), input.cached(cache, settings), "pack removed, original pixels restored");
        Settings disabled = new Settings(false, false, 0, Collections.emptySet());
        check(input.cached(cache, disabled) == null, "settings changed");
        same(AtlasSpriteOptimizer.optimize("test", 128, 128, 32, 32, input::pixel, 4, settings),
            cache.optimize(input.id, "test", 128, 128, 32, 32, input::pixel, 4, settings), "animation layout changed");
        same(AtlasSpriteOptimizer.optimize("test", 128, 128, 128, 128, input::pixel, 0, settings),
            cache.optimize(input.id, "test", 128, 128, 128, 128, input::pixel, 0, settings), "mipmap changed");
        // An animation changes output only through its frame layout. Its timing/order/interpolation
        // metadata is deliberately not cached: the mixin passes each current SpriteContents metadata.
        AtlasSpriteCache tiny = new AtlasSpriteCache(1024, 1);
        input.cached(tiny, settings);
        new Input("test:other", "test", 128, 128, 128, 128, pixels).cached(tiny, settings);
        check(tiny.size() == 1 && tiny.retainedBytes() == 1024, "entry eviction and byte budget");
        input.cached(tiny, settings);
        check(tiny.hits() == 0, "evicted entry recomputed");
        AtlasSpriteCache off = new AtlasSpriteCache(0, 0);
        same(input.original(settings), input.cached(off, settings), "disabled cache");
        check(off.size() == 0 && off.retainedBytes() == 0, "disabled retains nothing");
        System.out.println("AtlasSpriteProbe: content/settings/frame/mipmap invalidation, ownership and bounded eviction passed");
    }

    private static void same(Result expected, Result actual, String message) {
        if (expected == null || actual == null) {
            check(expected == actual, message);
        } else {
            check(expected.imageWidth == actual.imageWidth && expected.imageHeight == actual.imageHeight
                && expected.frameWidth == actual.frameWidth && expected.frameHeight == actual.frameHeight
                && expected.exactOnly == actual.exactOnly && Arrays.equals(expected.pixels, actual.pixels), message);
        }
    }

    private static void benchmarkCache(List<Input> inputs, Settings settings) {
        AtlasSpriteCache cache = new AtlasSpriteCache(32 * 1024 * 1024, 512);
        long coldStart = System.nanoTime();
        for (Input input : inputs) input.cached(cache, settings);
        double coldMs = (System.nanoTime() - coldStart) / 1e6;
        for (Input input : inputs) same(input.original(settings), input.cached(cache, settings), input.id);
        double[] original = new double[5], cached = new double[5];
        for (int i = -1; i < original.length; i++) {
            long start = System.nanoTime();
            for (Input input : inputs) input.original(settings);
            double elapsed = (System.nanoTime() - start) / 1e6;
            if (i >= 0) original[i] = elapsed;
            start = System.nanoTime();
            for (Input input : inputs) input.cached(cache, settings);
            elapsed = (System.nanoTime() - start) / 1e6;
            if (i >= 0) cached[i] = elapsed;
        }
        Arrays.sort(original);
        Arrays.sort(cached);
        System.out.printf("AtlasSpriteProbe: transform-only Java pixel arrays, cold cached %.3f ms; 5-run medians original %.3f ms, warm cache %.3f ms; %d entries / %d bytes. Excludes PNG decoding, native reads, stitching, upload and model reload.%n",
            coldMs, original[2], cached[2], cache.size(), cache.retainedBytes());
    }

    private static Integer number(String text, String key) {
        final Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static int[] randomPixels(Random random, int count) {
        final int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            pixels[i] = random.nextInt() | 0xFF000000;
        }
        return pixels;
    }

    private static int[] enlarge(int[] pixels, int width, int height, int factor) {
        final int[] result = new int[width * factor * height * factor];
        for (int y = 0; y < height * factor; y++) {
            for (int x = 0; x < width * factor; x++) {
                result[y * width * factor + x] = pixels[(y / factor) * width + x / factor];
            }
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
