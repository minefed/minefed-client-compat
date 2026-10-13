/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.scena;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers only Scena's negative loader check, never parsed or baked model objects. */
public final class ScenaAbsentLoaderCache {
    private static final ScenaAbsentLoaderCache SHARED = new ScenaAbsentLoaderCache(256, 2 * 1024 * 1024);
    private static final int ENTRY_OVERHEAD = 128;
    private final int maxEntries;
    private final int maxBytes;
    private final Map<String, Boolean> absent = new LinkedHashMap<>(16, 0.75F, true);
    private int retainedBytes;
    private long hits;
    private long misses;

    public ScenaAbsentLoaderCache(int maxEntries, int maxBytes) {
        if (maxEntries < 1 || maxBytes < ENTRY_OVERHEAD) throw new IllegalArgumentException("Invalid cache budget");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public static Object read(Gson gson, Reader reader, Class<?> type) {
        return type == JsonElement.class ? SHARED.readElement(gson, reader) : gson.fromJson(reader, type);
    }

    public static Statistics sharedStatistics() { return SHARED.statistics(); }

    public JsonElement readElement(Gson gson, Reader reader) {
        // Scena creates three Gson instances. Their pinned 2.10.1 default tree adapters
        // are equivalent, but another adapter's values or errors must never be memoized.
        if (!DefaultTreeAdapter.matches(gson)) return gson.fromJson(reader, JsonElement.class);
        // Scena opens and closes this reader. Preserve its decoding and ownership.
        final StringBuilder prefix = new StringBuilder();
        final char[] buffer = new char[1024];
        try {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                prefix.append(buffer, 0, count);
                if (weight(prefix.length()) > maxBytes) {
                    // The original parser still sees the entire document, including consumed input.
                    return gson.fromJson(new ReplayReader(prefix.toString(), reader), JsonElement.class);
                }
            }
        } catch (IOException failure) {
            // Let Gson encounter the same prefix and failure so its syntax/I/O exception policy wins.
            return gson.fromJson(new ReplayReader(prefix.toString(), new FailedReader(failure)), JsonElement.class);
        }
        final String content = prefix.toString();
        if (isAbsent(content)) {
            // Scena only tests isJsonObject() and has("loader") before returning null. Never share a mutable tree.
            return new JsonObject();
        }
        final JsonElement parsed = gson.fromJson(new StringReader(content), JsonElement.class);
        if (parsed != null && parsed.isJsonObject() && !parsed.getAsJsonObject().has("loader")) remember(content);
        return parsed;
    }

    private synchronized boolean isAbsent(String content) {
        if (absent.get(content) != null) { hits++; return true; }
        misses++;
        return false;
    }

    private synchronized void remember(String content) {
        if (absent.put(content, Boolean.TRUE) != null) return;
        retainedBytes += (int) weight(content.length());
        final var iterator = absent.keySet().iterator();
        while (absent.size() > maxEntries || retainedBytes > maxBytes) {
            retainedBytes -= (int) weight(iterator.next().length());
            iterator.remove();
        }
    }

    // Conservative UTF-16 payload plus key, backing-array and map-entry overhead; not a live-heap measurement.
    private static long weight(int chars) { return ENTRY_OVERHEAD + 2L * chars; }

    public synchronized Statistics statistics() { return new Statistics(hits, misses, absent.size(), retainedBytes); }
    public record Statistics(long hits, long misses, int entries, int retainedBytes) {}

    private static final class DefaultTreeAdapter {
        private static final Field FACTORY;
        private static final Field REQUESTED_TYPE;
        private static final Field LENIENT;

        static {
            Field factory = null, requestedType = null, lenient = null;
            try {
                Class<?> adapter = new Gson().getAdapter(JsonElement.class).getClass();
                // Internal layout is intentionally pinned to Gson 2.10.1. Other layouts fall back.
                if (adapter.getName().equals("com.google.gson.internal.bind.TypeAdapters$34$1")) {
                    factory = adapter.getDeclaredField("this$0");
                    requestedType = adapter.getDeclaredField("val$requestedType");
                    lenient = Gson.class.getDeclaredField("lenient");
                    factory.setAccessible(true);
                    requestedType.setAccessible(true);
                    lenient.setAccessible(true);
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                factory = null;
                requestedType = null;
                lenient = null;
            }
            FACTORY = factory;
            REQUESTED_TYPE = requestedType;
            LENIENT = lenient;
        }

        private static boolean matches(Gson gson) {
            if (FACTORY == null) return false;
            try {
                TypeAdapter<JsonElement> adapter = gson.getAdapter(JsonElement.class);
                return adapter.getClass() == FACTORY.getDeclaringClass()
                    && FACTORY.get(adapter) == TypeAdapters.JSON_ELEMENT_FACTORY
                    && REQUESTED_TYPE.get(adapter) == JsonElement.class
                    // Gson restores this setting before checking trailing input.
                    && !LENIENT.getBoolean(gson);
            } catch (ReflectiveOperationException | RuntimeException unsupported) {
                return false;
            }
        }
    }

    private static final class ReplayReader extends Reader {
        private final StringReader prefix;
        private final Reader remainder;
        private ReplayReader(String prefix, Reader remainder) { this.prefix = new StringReader(prefix); this.remainder = remainder; }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            int count = prefix.read(chars, offset, length);
            return count < 0 ? remainder.read(chars, offset, length) : count;
        }
        @Override public void close() {} // The enclosing Scena method owns the original reader.
    }

    private static final class FailedReader extends Reader {
        private final IOException failure;
        private FailedReader(IOException failure) { this.failure = failure; }
        @Override public int read(char[] chars, int offset, int length) throws IOException { throw failure; }
        @Override public void close() {}
    }
}
