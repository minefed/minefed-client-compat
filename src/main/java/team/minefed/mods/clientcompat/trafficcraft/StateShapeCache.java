/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.trafficcraft;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_265;
import net.minecraft.class_2680;

/**
 * Outline shapes of TrafficCraft blocks whose getShape reads nothing but the block state.
 * Block states are unique per block, so one map serves all of them. Shape builders may run
 * on chunk-builder threads, hence the concurrent map; the first stored shape wins.
 */
public final class StateShapeCache {
    private static final ConcurrentHashMap<class_2680, class_265> SHAPES = new ConcurrentHashMap<>();

    private StateShapeCache() {}

    public static class_265 get(class_2680 state) {
        return SHAPES.get(state);
    }

    public static class_265 put(class_2680 state, class_265 shape) {
        if (shape == null) return null;
        class_265 previous = SHAPES.putIfAbsent(state, shape);
        return previous != null ? previous : shape;
    }
}
