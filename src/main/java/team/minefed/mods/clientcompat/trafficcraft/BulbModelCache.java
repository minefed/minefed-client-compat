/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.trafficcraft;

import de.mrjulsen.trafficcraft.client.TrafficLightTextureManager.TrafficLightTextureKey;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The bulb model TrafficCraft's own lookup chose for each icon/color pair. Its key equality
 * compares exactly these two enum values and the model list never changes after class
 * initialization, so the first result is the result of every later lookup.
 */
public final class BulbModelCache {
    private static final int LIMIT = 64;
    private static final AtomicReferenceArray<Object> MODELS = new AtomicReferenceArray<>(LIMIT * LIMIT);

    private BulbModelCache() {}

    public static Object get(TrafficLightTextureKey key) {
        int slot = slot(key);
        return slot < 0 ? null : MODELS.get(slot);
    }

    public static Object put(TrafficLightTextureKey key, Object model) {
        int slot = slot(key);
        if (slot >= 0 && model != null) MODELS.compareAndSet(slot, null, model);
        return model;
    }

    private static int slot(TrafficLightTextureKey key) {
        if (key == null) return -1;
        Enum<?> icon = key.getIcon(), color = key.getColor();
        if (icon == null || color == null || icon.ordinal() >= LIMIT || color.ordinal() >= LIMIT) return -1;
        return icon.ordinal() * LIMIT + color.ordinal();
    }
}
