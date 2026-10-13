/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import team.minefed.mods.clientcompat.model.ModelVariantIndex;

/** Pure algorithm checks; timing is intentionally excluded from this correctness probe. */
public final class ModelVariantIndexProbe {
    private static final Object BOOKS = new Object(), FACING = new Object(), SEED = new Object();
    private enum Facing { NORTH, SOUTH, EAST, WEST }
    private record State(Map<Object, Object> values) {}
    private record CustomValue(int value) implements Comparable<CustomValue> {
        public int compareTo(CustomValue other) { return Integer.compare(value, other.value); }
    }

    public static void main(String[] args) {
        List<State> states = new ArrayList<>();
        for (int books = 1; books <= 4; books++) for (Facing facing : Facing.values()) for (int seed = 1; seed <= 256; seed++) {
            states.add(new State(Map.of(BOOKS, books, FACING, facing, SEED, seed)));
        }
        states = List.copyOf(states);
        ModelVariantIndex<State> index = new ModelVariantIndex<>(State::values);
        AtomicInteger calls = new AtomicInteger();
        int queries = 0;
        for (int books = 1; books <= 4; books++) for (Facing facing : Facing.values()) for (int seed : new int[]{1, 127, 128, 129, 256}) {
            verify(index, states, Map.of(BOOKS, books, FACING, facing, SEED, seed), calls);
            queries++;
        }
        check(calls.get() == states.size() + queries - 1, "one original scan, then one predicate per full-state candidate");
        int acceleratedCalls = calls.get();
        verify(index, states, Map.of(SEED, 256), calls);
        verify(index, states, Map.of(BOOKS, 2, FACING, Facing.WEST), calls);
        verify(index, states, Map.of(), calls);
        verify(index, states, Map.of(SEED, 999), calls);
        verify(index, states, Map.of(new Object(), 1), calls);

        // Simulates the already parsed map: duplicate keys have last-value semantics and
        // noncanonical spellings such as "01" have already become the Integer value 1.
        Map<Object, Object> parsed = new LinkedHashMap<>();
        parsed.put(SEED, 256); parsed.put(SEED, Integer.valueOf("01"));
        verify(index, states, parsed, calls);
        List<State> reverse = new ArrayList<>(states);
        java.util.Collections.reverse(reverse);
        verify(index, reverse, Map.of(FACING, Facing.NORTH), calls);
        verify(index, reverse, Map.of(FACING, Facing.NORTH), calls);

        // Overlap handling/consumer errors remain downstream of filtering, as in vanilla.
        AtomicInteger visits = new AtomicInteger();
        Predicate<State> original = state -> Objects.equals(state.values.get(BOOKS), 2);
        try {
            index.filter(reverse.stream(), reverse, ModelVariantIndex.preserve(original, Map.of(BOOKS, 2)))
                .forEach(state -> { if (visits.incrementAndGet() == 3) throw new IllegalStateException("overlap"); });
            throw new AssertionError("expected consumer exception");
        } catch (IllegalStateException expected) {
            check("overlap".equals(expected.getMessage()) && visits.get() == 3, "consumer ordering and exception unchanged");
        }
        AtomicInteger boundedReads = new AtomicInteger();
        java.util.function.Function<State, Map<Object, Object>> countedEntries = state -> {
            boundedReads.incrementAndGet(); return state.values;
        };
        fallback(List.of(states.get(0)), countedEntries, "small list");
        fallback(java.util.Collections.nCopies(32_769, states.get(0)), countedEntries, "state count bound");
        check(boundedReads.get() == 0, "small/oversized lists never start index allocation");
        fallback(java.util.Collections.nCopies(64, states.get(0)), State::values, "duplicate identities");
        List<State> withNull = new ArrayList<>(states.subList(0, 64)); withNull.set(10, null);
        fallback(withNull, state -> state == null ? null : state.values, "null state");
        fallback(states.subList(0, 64), state -> { throw new IllegalArgumentException("custom"); }, "throwing/custom state");
        List<State> custom = new ArrayList<>();
        for (int i = 0; i < 64; i++) custom.add(new State(Map.of(SEED, new CustomValue(i))));
        fallback(custom, State::values, "custom Comparable", Map.of(SEED, new CustomValue(1)));
        List<State> heterogeneous = new ArrayList<>(states.subList(0, 64));
        heterogeneous.set(10, new State(Map.of(new Object(), 1, new Object(), 2, new Object(), 3)));
        fallback(heterogeneous, State::values, "heterogeneous property identities");
        Map<Object, Object> manyProperties = new IdentityHashMap<>();
        for (int i = 0; i < 65; i++) manyProperties.put(new Object(), i);
        List<State> wide = new ArrayList<>();
        for (int i = 0; i < 4096; i++) wide.add(new State(manyProperties));
        AtomicInteger wideReads = new AtomicInteger();
        fallback(wide, state -> { wideReads.incrementAndGet(); return state.values; }, "membership bound");
        check(wideReads.get() > 0 && wideReads.get() < wide.size(), "membership cap stops construction before all states are retained");
        System.out.println("PASS: full/partial/empty/absent constraints, parsed aliases/duplicate keys, Integer 128+, order, list replacement, exceptions and bounded/custom fallbacks; "
            + queries + " full-state queries call the original predicate " + acceleratedCalls + " times instead of " + (queries * states.size()) + ".");
    }

    private static void verify(ModelVariantIndex<State> index, List<State> states, Map<Object, Object> conditions, AtomicInteger calls) {
        Predicate<State> original = state -> conditions.entrySet().stream().allMatch(entry -> Objects.equals(state.values.get(entry.getKey()), entry.getValue()));
        List<State> expected = states.stream().filter(original).toList();
        Predicate<State> counted = state -> { calls.incrementAndGet(); return original.test(state); };
        List<State> actual = index.filter(states.stream(), states, ModelVariantIndex.preserve(counted, conditions)).toList();
        check(expected.size() == actual.size(), "same match count");
        for (int i = 0; i < actual.size(); i++) check(actual.get(i) == expected.get(i), "same identity and order");
    }

    private static void fallback(List<State> states, java.util.function.Function<State, Map<Object, Object>> entries, String label) {
        fallback(states, entries, label, Map.of(SEED, 1));
    }

    private static void fallback(List<State> states, java.util.function.Function<State, Map<Object, Object>> entries, String label,
            Map<Object, Object> conditions) {
        ModelVariantIndex<State> index = new ModelVariantIndex<>(entries);
        AtomicInteger calls = new AtomicInteger();
        Predicate<State> predicate = ModelVariantIndex.preserve(state -> { calls.incrementAndGet(); return true; }, conditions);
        for (int i = 0; i < 2; i++) check(index.filter(states.stream(), states, predicate).count() == states.size(), label);
        check(calls.get() == states.size() * 2, label + " uses the original scan");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
