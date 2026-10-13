/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.atlas;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, content-validated reuse of pixel transforms across resource reloads. */
public final class AtlasSpriteCache {
    private final long budgetBytes;
    private final int maxEntries;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;
    private long hits;

    public AtlasSpriteCache(long budgetBytes, int maxEntries) {
        this.budgetBytes = Math.max(0, budgetBytes);
        this.maxEntries = Math.max(0, maxEntries);
    }

    public AtlasSpriteOptimizer.Result optimize(String id, String namespace, int width, int height,
            int frameWidth, int frameHeight, AtlasSpriteOptimizer.PixelSource source, int mipLevel,
            AtlasSpriteOptimizer.Settings settings) {
        Key key = new Key(id, namespace, width, height, frameWidth, frameHeight, mipLevel,
            settings.exactDownscale, settings.mipmapAlignment, settings.maxStaticSize,
            settings.sizeCapExcludedNamespaces.contains(namespace));
        Entry previous;
        synchronized (this) {
            previous = entries.get(key);
        }
        byte[] fingerprint = null;
        if (previous != null) {
            fingerprint = fingerprint(source, width, height);
            if (Arrays.equals(previous.fingerprint, fingerprint)) {
                synchronized (this) { hits++; }
                return copy(previous.result);
            }
        }
        AtlasSpriteOptimizer.Result result = AtlasSpriteOptimizer.optimize(namespace, width, height,
            frameWidth, frameHeight, source, mipLevel, settings);
        // Most sprites return unchanged after a few pixel reads. Never add a full-image scan for them.
        if (result != null && maxEntries > 0 && cost(result) <= budgetBytes) {
            if (fingerprint == null) fingerprint = fingerprint(source, width, height);
            put(key, new Entry(fingerprint, copy(result)));
        } else {
            synchronized (this) {
                Entry removed = entries.remove(key);
                if (removed != null) retainedBytes -= cost(removed.result);
            }
        }
        return result;
    }

    public synchronized long hits() { return hits; }
    public synchronized long retainedBytes() { return retainedBytes; }
    public synchronized int size() { return entries.size(); }

    private synchronized void put(Key key, Entry entry) {
        Entry old = entries.put(key, entry);
        if (old != null) retainedBytes -= cost(old.result);
        retainedBytes += cost(entry.result);
        Iterator<Entry> iterator = entries.values().iterator();
        while (retainedBytes > budgetBytes || entries.size() > maxEntries) {
            retainedBytes -= cost(iterator.next().result);
            iterator.remove();
        }
    }

    private static long cost(AtlasSpriteOptimizer.Result result) {
        return (long) result.pixels.length * Integer.BYTES;
    }

    private static AtlasSpriteOptimizer.Result copy(AtlasSpriteOptimizer.Result result) {
        return new AtlasSpriteOptimizer.Result(result.imageWidth, result.imageHeight,
            result.frameWidth, result.frameHeight, result.pixels.clone(), result.exactOnly);
    }

    private static byte[] fingerprint(AtlasSpriteOptimizer.PixelSource source, int width, int height) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[16 * 1024];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = source.get(x, y);
                buffer[offset++] = (byte) pixel;
                buffer[offset++] = (byte) (pixel >>> 8);
                buffer[offset++] = (byte) (pixel >>> 16);
                buffer[offset++] = (byte) (pixel >>> 24);
                if (offset == buffer.length) {
                    digest.update(buffer);
                    offset = 0;
                }
            }
        }
        digest.update(buffer, 0, offset);
        return digest.digest();
    }

    private record Key(String id, String namespace, int width, int height, int frameWidth, int frameHeight,
            int mipLevel, boolean exact, boolean align, int maxStaticSize, boolean excluded) {}
    private record Entry(byte[] fingerprint, AtlasSpriteOptimizer.Result result) {}
}
