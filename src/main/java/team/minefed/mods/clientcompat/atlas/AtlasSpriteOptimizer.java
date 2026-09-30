/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Decides how a block atlas sprite is stored before the atlas is stitched. Colours are 32-bit ints
 * with alpha in the top byte (NativeImage ABGR or AWT ARGB); the other three bytes are treated alike.
 * <ol>
 * <li><b>Exact downscale.</b> A static sprite that is an exact nearest-neighbour enlargement (every
 * f×f block has one colour) is stored at 1/f size. Every texel samples the same colour as before.</li>
 * <li><b>Size cap.</b> Static sprites larger than {@link Settings#maxStaticSize} are halved with a
 * premultiplied box filter while both sides stay multiples of the mipmap alignment. This changes
 * pixels and can be disabled or limited per namespace.</li>
 * <li><b>Mipmap alignment.</b> Vanilla lowers the mipmap level of the whole atlas to the smallest
 * power of two dividing any sprite side. Sprites below the alignment are enlarged with nearest
 * neighbour (same texels at full size) when that stays small, otherwise resampled to the nearest
 * multiple of the alignment.</li>
 * </ol>
 */
public final class AtlasSpriteOptimizer {

    /** Largest side produced by the lossless nearest-neighbour enlargement for mipmap alignment. */
    static final int MAX_UPSCALED_SIZE = 512;
    /** Larger factors would multiply the side quads of generated item models; such sprites are resampled instead. */
    static final int MAX_UPSCALE_FACTOR = 4;

    private AtlasSpriteOptimizer() {
    }

    @FunctionalInterface
    public interface PixelSource {
        int get(int x, int y);
    }

    public static final class Result {
        public final int imageWidth;
        public final int imageHeight;
        public final int frameWidth;
        public final int frameHeight;
        public final int[] pixels;
        public final boolean exactOnly;

        Result(int imageWidth, int imageHeight, int frameWidth, int frameHeight, int[] pixels, boolean exactOnly) {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.pixels = pixels;
            this.exactOnly = exactOnly;
        }

        public int get(int x, int y) {
            return pixels[y * imageWidth + x];
        }
    }

    public static final class Settings {
        public final boolean exactDownscale;
        public final boolean mipmapAlignment;
        public final int maxStaticSize;
        public final Set<String> sizeCapExcludedNamespaces;

        public Settings(boolean exactDownscale, boolean mipmapAlignment, int maxStaticSize, Set<String> sizeCapExcludedNamespaces) {
            this.exactDownscale = exactDownscale;
            this.mipmapAlignment = mipmapAlignment;
            this.maxStaticSize = maxStaticSize;
            this.sizeCapExcludedNamespaces = sizeCapExcludedNamespaces;
        }

        public static Settings defaults() {
            return new Settings(true, true, 512, new HashSet<>(Arrays.asList("msd")));
        }

        /**
         * Reads {@code config/minefed-atlas.properties} (keys {@code exactDownscale}, {@code mipmapAlignment},
         * {@code maxStaticSize}, {@code sizeCapExcludedNamespaces}); system properties prefixed with
         * {@code minefed.atlas.} take precedence. Missing or invalid values keep the defaults.
         */
        public static Settings load() {
            final Settings defaults = defaults();
            final Properties properties = new Properties();
            final Path path = Paths.get("config", "minefed-atlas.properties");
            if (Files.isRegularFile(path)) {
                try (InputStream inputStream = Files.newInputStream(path)) {
                    properties.load(inputStream);
                } catch (IOException ignored) {
                    // Keep the defaults
                }
            }
            final boolean exactDownscale = Boolean.parseBoolean(value(properties, "exactDownscale", String.valueOf(defaults.exactDownscale)));
            final boolean mipmapAlignment = Boolean.parseBoolean(value(properties, "mipmapAlignment", String.valueOf(defaults.mipmapAlignment)));
            int maxStaticSize = defaults.maxStaticSize;
            try {
                maxStaticSize = Integer.parseInt(value(properties, "maxStaticSize", String.valueOf(defaults.maxStaticSize)).trim());
            } catch (NumberFormatException ignored) {
                // Keep the default
            }
            final Set<String> excluded = new HashSet<>();
            for (final String namespace : value(properties, "sizeCapExcludedNamespaces", String.join(",", defaults.sizeCapExcludedNamespaces)).split(",")) {
                if (!namespace.trim().isEmpty()) {
                    excluded.add(namespace.trim().toLowerCase(Locale.ROOT));
                }
            }
            return new Settings(exactDownscale, mipmapAlignment, maxStaticSize, excluded);
        }

        private static String value(Properties properties, String key, String fallback) {
            return System.getProperty("minefed.atlas." + key, properties.getProperty(key, fallback));
        }
    }

    /**
     * @param namespace   the sprite namespace
     * @param imageWidth  the width of the whole image (all animation frames)
     * @param imageHeight the height of the whole image
     * @param frameWidth  the sprite (frame) width
     * @param frameHeight the sprite (frame) height
     * @param source      reads the original image
     * @param mipLevel    the atlas mipmap level requested by the options
     * @return the new image, or {@code null} to keep the sprite unchanged
     */
    public static Result optimize(String namespace, int imageWidth, int imageHeight, int frameWidth, int frameHeight, PixelSource source, int mipLevel, Settings settings) {
        if (frameWidth <= 0 || frameHeight <= 0 || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
            return null;
        }
        final int alignment = 1 << Math.max(0, Math.min(mipLevel, 4));
        final boolean animated = imageWidth != frameWidth || imageHeight != frameHeight;
        final int columns = imageWidth / frameWidth;
        final int rows = imageHeight / frameHeight;
        final int originalAlignment = Math.min(Integer.lowestOneBit(frameWidth), Integer.lowestOneBit(frameHeight));

        int width = frameWidth;
        int height = frameHeight;
        int[] pixels = null;
        boolean exactOnly = true;

        if (!animated && settings.exactDownscale && width * height >= 32 * 32) {
            // Keep at least the alignment the sprite already had, up to the requested one
            final int keepAlignment = Math.min(alignment, originalAlignment);
            int factor = 1;
            while (canShrink(width / factor, height / factor, keepAlignment) && isUniformBlocks(source, width, height, factor * 2)) {
                factor *= 2;
            }
            if (factor > 1) {
                pixels = sampleEvery(source, width, height, factor);
                width /= factor;
                height /= factor;
            }
        }

        if (!animated && settings.maxStaticSize > 0 && !settings.sizeCapExcludedNamespaces.contains(namespace)) {
            while (Math.max(width, height) > settings.maxStaticSize && width % (alignment * 2) == 0 && height % (alignment * 2) == 0) {
                pixels = halve(pixels == null ? copy(source, width, height) : pixels, width, height);
                width /= 2;
                height /= 2;
                exactOnly = false;
            }
        }

        if (settings.mipmapAlignment && Math.min(Integer.lowestOneBit(width), Integer.lowestOneBit(height)) < alignment) {
            int scale = 1;
            while (Math.min(Integer.lowestOneBit(width * scale), Integer.lowestOneBit(height * scale)) < alignment) {
                scale *= 2;
            }
            final int[] framePixels = pixels;
            final int currentWidth = width;
            final int currentHeight = height;
            final PixelSource current = framePixels == null ? source : (x, y) -> framePixels[y * currentWidth * columns + x];
            if (scale <= MAX_UPSCALE_FACTOR && Math.max(width, height) * scale <= MAX_UPSCALED_SIZE) {
                pixels = enlarge(current, width * columns, height * rows, scale);
                width *= scale;
                height *= scale;
            } else {
                final int newWidth = roundToMultiple(width, alignment);
                final int newHeight = roundToMultiple(height, alignment);
                pixels = resampleFrames(current, currentWidth, currentHeight, columns, rows, newWidth, newHeight);
                width = newWidth;
                height = newHeight;
                exactOnly = false;
            }
        }

        return pixels == null ? null : new Result(width * columns, height * rows, width, height, pixels, exactOnly);
    }

    static boolean canShrink(int width, int height, int keepAlignment) {
        return width % 2 == 0 && height % 2 == 0 && width / 2 >= 16 && height / 2 >= 16
                && Integer.lowestOneBit(width / 2) >= keepAlignment && Integer.lowestOneBit(height / 2) >= keepAlignment;
    }

    /** Every factor×factor block has a single colour; returns early at the first difference. */
    static boolean isUniformBlocks(PixelSource source, int width, int height, int factor) {
        if (width % factor != 0 || height % factor != 0) {
            return false;
        }
        for (int blockY = 0; blockY < height; blockY += factor) {
            for (int blockX = 0; blockX < width; blockX += factor) {
                final int color = source.get(blockX, blockY);
                for (int y = blockY; y < blockY + factor; y++) {
                    for (int x = blockX; x < blockX + factor; x++) {
                        if (source.get(x, y) != color) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    static int roundToMultiple(int value, int alignment) {
        return Math.max(alignment, Math.round((float) value / alignment) * alignment);
    }

    private static int[] copy(PixelSource source, int width, int height) {
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = source.get(x, y);
            }
        }
        return pixels;
    }

    private static int[] sampleEvery(PixelSource source, int width, int height, int factor) {
        final int newWidth = width / factor;
        final int newHeight = height / factor;
        final int[] pixels = new int[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                pixels[y * newWidth + x] = source.get(x * factor, y * factor);
            }
        }
        return pixels;
    }

    private static int[] enlarge(PixelSource source, int width, int height, int scale) {
        final int newWidth = width * scale;
        final int[] pixels = new int[newWidth * height * scale];
        for (int y = 0; y < height * scale; y++) {
            for (int x = 0; x < newWidth; x++) {
                pixels[y * newWidth + x] = source.get(x / scale, y / scale);
            }
        }
        return pixels;
    }

    /** Premultiplied 2×2 box filter. */
    private static int[] halve(int[] pixels, int width, int height) {
        final int newWidth = width / 2;
        final int newHeight = height / 2;
        final int[] result = new int[newWidth * newHeight];
        final long[] sum = new long[4];
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) {
                Arrays.fill(sum, 0);
                accumulate(sum, pixels[(2 * y) * width + 2 * x], 1);
                accumulate(sum, pixels[(2 * y) * width + 2 * x + 1], 1);
                accumulate(sum, pixels[(2 * y + 1) * width + 2 * x], 1);
                accumulate(sum, pixels[(2 * y + 1) * width + 2 * x + 1], 1);
                result[y * newWidth + x] = unpremultiply(sum, 4);
            }
        }
        return result;
    }

    /** Premultiplied bilinear resampling of every frame to a new frame size. */
    private static int[] resampleFrames(PixelSource source, int frameWidth, int frameHeight, int columns, int rows, int newFrameWidth, int newFrameHeight) {
        final int newImageWidth = newFrameWidth * columns;
        final int[] pixels = new int[newImageWidth * newFrameHeight * rows];
        final long[] sum = new long[4];
        final int weightScale = 256;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                for (int y = 0; y < newFrameHeight; y++) {
                    final double sourceY = Math.max(0, Math.min(frameHeight - 1, (y + 0.5) * frameHeight / newFrameHeight - 0.5));
                    final int y0 = (int) Math.floor(sourceY);
                    final int y1 = Math.min(frameHeight - 1, y0 + 1);
                    final int weightY = (int) Math.round((sourceY - y0) * weightScale);
                    for (int x = 0; x < newFrameWidth; x++) {
                        final double sourceX = Math.max(0, Math.min(frameWidth - 1, (x + 0.5) * frameWidth / newFrameWidth - 0.5));
                        final int x0 = (int) Math.floor(sourceX);
                        final int x1 = Math.min(frameWidth - 1, x0 + 1);
                        final int weightX = (int) Math.round((sourceX - x0) * weightScale);
                        final int offsetX = column * frameWidth;
                        final int offsetY = row * frameHeight;
                        Arrays.fill(sum, 0);
                        accumulate(sum, source.get(offsetX + x0, offsetY + y0), (weightScale - weightX) * (weightScale - weightY));
                        accumulate(sum, source.get(offsetX + x1, offsetY + y0), weightX * (weightScale - weightY));
                        accumulate(sum, source.get(offsetX + x0, offsetY + y1), (weightScale - weightX) * weightY);
                        accumulate(sum, source.get(offsetX + x1, offsetY + y1), weightX * weightY);
                        pixels[(row * newFrameHeight + y) * newImageWidth + column * newFrameWidth + x] = unpremultiply(sum, weightScale * weightScale);
                    }
                }
            }
        }
        return pixels;
    }

    private static void accumulate(long[] sum, int color, int weight) {
        final int alpha = color >>> 24;
        sum[0] += (long) alpha * weight;
        sum[1] += (long) (color & 0xFF) * alpha * weight;
        sum[2] += (long) (color >>> 8 & 0xFF) * alpha * weight;
        sum[3] += (long) (color >>> 16 & 0xFF) * alpha * weight;
    }

    private static int unpremultiply(long[] sum, int totalWeight) {
        final long alphaSum = sum[0];
        final int alpha = (int) ((alphaSum + totalWeight / 2) / totalWeight);
        if (alphaSum == 0) {
            return 0;
        }
        final int c0 = (int) Math.min(255, (sum[1] + alphaSum / 2) / alphaSum);
        final int c1 = (int) Math.min(255, (sum[2] + alphaSum / 2) / alphaSum);
        final int c2 = (int) Math.min(255, (sum[3] + alphaSum / 2) / alphaSum);
        return alpha << 24 | c2 << 16 | c1 << 8 | c0;
    }
}
