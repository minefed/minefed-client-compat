/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Narrows a vanilla variant predicate to actual states, retaining its final validation. */
public final class ModelVariantIndex<T> {
    private static final int MIN_STATES = 64;
    private static final int MAX_STATES = 32_768;
    private static final int MAX_MEMBERSHIPS = 262_144;
    private final Function<? super T, ? extends Map<?, ?>> entries;
    // The caller keeps one instance per loading thread. No model, texture or pack is retained.
    private List<T> recentStates;
    private IdentityHashMap<Object, Map<Object, Positions>> recentIndex;
    private boolean attempted;

    public ModelVariantIndex(Function<? super T, ? extends Map<?, ?>> entries) {
        this.entries = entries;
    }

    /** The map comes from vanilla's parser, after duplicate keys and aliases were resolved. */
    public static <T> Predicate<T> preserve(Predicate<T> original, Map<?, ?> parsedConditions) {
        if (parsedConditions.isEmpty()) return original;
        return new ParsedPredicate<>(original, parsedConditions);
    }

    public Stream<T> filter(Stream<T> originalStream, List<T> states, Predicate<? super T> predicate) {
        if (!(predicate instanceof ParsedPredicate<?> parsed) || states.size() < MIN_STATES
                || states.size() > MAX_STATES) return originalStream.filter(predicate);
        if (recentStates != states) {
            recentStates = states;
            recentIndex = null;
            attempted = false;
            // A list used by only one variant should pay no indexing cost.
            return originalStream.filter(predicate);
        }
        if (!attempted) {
            attempted = true;
            try {
                recentIndex = build(states);
            } catch (RuntimeException unsupportedState) {
                recentIndex = null;
            }
        }
        List<T> candidates = candidates(states, parsed.conditions);
        if (candidates == null) return originalStream.filter(predicate);
        // Selection is in original list order; vanilla still evaluates its predicate and forEach.
        return candidates.stream().onClose(originalStream::close).filter(predicate);
    }

    private IdentityHashMap<Object, Map<Object, Positions>> build(List<T> states) {
        IdentityHashMap<Object, Map<Object, Positions>> index = new IdentityHashMap<>();
        IdentityHashMap<T, Boolean> uniqueStates = new IdentityHashMap<>();
        int memberships = 0;
        int propertyCount = -1;
        for (int position = 0; position < states.size(); position++) {
            T state = states.get(position);
            if (state == null || uniqueStates.put(state, Boolean.TRUE) != null) return null;
            Map<?, ?> properties = entries.apply(state);
            if (properties == null || properties.isEmpty()) return null;
            if (propertyCount < 0) propertyCount = properties.size();
            if (propertyCount != properties.size()) return null;
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                Object key = property.getKey(), value = property.getValue();
                if (key == null || !standardValue(value) || ++memberships > MAX_MEMBERSHIPS) return null;
                Map<Object, Positions> values = index.get(key);
                if (values == null) {
                    if (position != 0) return null; // A heterogeneous/custom state list is not indexed.
                    values = new HashMap<>();
                    index.put(key, values);
                }
                values.computeIfAbsent(value, ignored -> new Positions()).add(position);
            }
        }
        return index;
    }

    private List<T> candidates(List<T> states, Map<?, ?> conditions) {
        if (recentIndex == null) return null;
        Positions[] postings = new Positions[conditions.size()];
        int count = 0;
        Positions shortest = null;
        for (Map.Entry<?, ?> condition : conditions.entrySet()) {
            if (!standardValue(condition.getValue())) return null;
            Map<Object, Positions> values = recentIndex.get(condition.getKey());
            // Missing identities/values may come from custom properties: let vanilla decide.
            if (values == null) return null;
            Positions positions = values.get(condition.getValue());
            if (positions == null) return null;
            postings[count++] = positions;
            if (shortest == null || positions.size < shortest.size) shortest = positions;
        }
        if (shortest == null) return null;
        List<T> result = new ArrayList<>(shortest.size);
        nextPosition: for (int i = 0; i < shortest.size; i++) {
            int position = shortest.values[i];
            for (Positions posting : postings) {
                if (posting != shortest && Arrays.binarySearch(posting.values, 0, posting.size, position) < 0) {
                    continue nextPosition;
                }
            }
            result.add(states.get(position));
        }
        return result;
    }

    private static boolean standardValue(Object value) {
        // These final value implementations obey symmetric value equality and stable hashes.
        // A custom Comparable can implement different semantics; it takes the original path.
        return value instanceof Integer || value instanceof Boolean || value instanceof Enum<?>;
    }

    private static final class ParsedPredicate<T> implements Predicate<T> {
        private final Predicate<T> original;
        private final IdentityHashMap<Object, Object> conditions = new IdentityHashMap<>();

        private ParsedPredicate(Predicate<T> original, Map<?, ?> conditions) {
            this.original = original;
            conditions.forEach(this.conditions::put);
        }

        @Override
        public boolean test(T state) {
            return original.test(state);
        }
    }

    private static final class Positions {
        private int[] values = new int[4];
        private int size;

        private void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }
}
