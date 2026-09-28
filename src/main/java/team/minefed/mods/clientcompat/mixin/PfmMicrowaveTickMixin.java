/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.unlikepaladin.pfm.blocks.blockentities.MicrowaveBlockEntity;
import java.util.Optional;
import net.minecraft.class_1263;
import net.minecraft.class_1863;
import net.minecraft.class_1937;
import net.minecraft.class_3956;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An idle microwave holding an item looks up its smoking recipe every server
 * tick, but reads the result only while it is running. The vanilla lookup only
 * filters recipes by matches(), so an idle microwave can skip it without any
 * observable change. isActive cannot change between the lookup and its use.
 */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.blocks.blockentities.MicrowaveBlockEntity", remap = false)
public abstract class PfmMicrowaveTickMixin {
    @WrapOperation(
        method = "tick(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;"
            + "Lcom/unlikepaladin/pfm/blocks/blockentities/MicrowaveBlockEntity;)V",
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/class_1863;method_8132(Lnet/minecraft/class_3956;Lnet/minecraft/class_1263;Lnet/minecraft/class_1937;)Ljava/util/Optional;"),
        remap = false, require = 1, expect = 1, allow = 1)
    private static Optional<?> minefed$skipIdleRecipeLookup(class_1863 recipes, class_3956<?> type, class_1263 container, class_1937 level,
            Operation<Optional<?>> original, @Local(argsOnly = true) MicrowaveBlockEntity microwave) {
        return microwave.isActive ? original.call(recipes, type, container, level) : Optional.empty();
    }
}
