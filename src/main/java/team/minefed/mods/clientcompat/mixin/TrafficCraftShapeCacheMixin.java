/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.class_1922;
import net.minecraft.class_2338;
import net.minecraft.class_265;
import net.minecraft.class_2680;
import net.minecraft.class_3726;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import team.minefed.mods.clientcompat.trafficcraft.StateShapeCache;

/**
 * These getShape methods read only their block state and constant shapes, but rebuild the
 * shape with up to seven Shapes.or calls (or a new box) on every outline and ray query.
 * Keep the first result per state. VoxelShapes are immutable, so later callers receive an
 * equal shape. Other blocks' states and null states still go straight to TrafficCraft.
 */
@Pseudo
@Mixin(targets = {
    "de.mrjulsen.trafficcraft.block.TrafficLightBlock",
    "de.mrjulsen.trafficcraft.block.TrafficSignPostBlock",
    "de.mrjulsen.trafficcraft.block.RoadSaltBlock"
}, remap = false)
public abstract class TrafficCraftShapeCacheMixin {
    @WrapMethod(method = "method_9530(Lnet/minecraft/class_2680;Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;Lnet/minecraft/class_3726;)Lnet/minecraft/class_265;",
        remap = false, require = 1, expect = 1, allow = 1)
    private class_265 minefed$shapeOfState(class_2680 state, class_1922 level, class_2338 pos, class_3726 context,
            Operation<class_265> original) {
        if (state == null || state.method_26204() != (Object) this) return original.call(state, level, pos, context);
        class_265 shape = StateShapeCache.get(state);
        return shape != null ? shape : StateShapeCache.put(state, original.call(state, level, pos, context));
    }
}
