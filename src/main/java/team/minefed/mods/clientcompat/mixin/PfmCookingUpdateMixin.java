/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import java.util.Optional;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2371;
import net.minecraft.class_2680;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A finished stove item without a campfire recipe stays on the burner when
 * foodPopsOffStove is false. PFM then stores the same stack again and sends a
 * block update plus the full block-entity NBT to every tracking player on each
 * tick. Skip only that identical store and its update; the recipe lookup, the
 * pop-off branch, real conversions and setChanged are unchanged.
 * Selectors are the pinned PFM 1.5.0 / Fabric 1.20.4 intermediary names.
 */
@Pseudo
@Mixin(targets = {
    "com.unlikepaladin.pfm.blocks.blockentities.StoveBlockEntity",
    "com.unlikepaladin.pfm.blocks.blockentities.StovetopBlockEntity"
}, remap = false)
public abstract class PfmCookingUpdateMixin {
    @WrapOperation(method = "litServerTick", remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;", remap = false))
    private static Object minefed$recordMissingResult(Optional<?> result, Object cooking, Operation<Object> original,
            @Share("noResult") LocalBooleanRef noResult) {
        noResult.set(result.isEmpty());
        return original.call(result, cooking);
    }

    /** Ordinal 1 is the keep-on-stove branch; ordinal 0 empties the slot after popping the item off. */
    @WrapWithCondition(method = "litServerTick", remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_2371;set(ILjava/lang/Object;)Ljava/lang/Object;", ordinal = 1, remap = false))
    private static boolean minefed$storeOnlyNewResult(class_2371<?> items, int slot, Object stack,
            @Share("noResult") LocalBooleanRef noResult, @Share("unchanged") LocalBooleanRef unchanged) {
        // Without a result, orElse returned the stack that is already in this slot.
        unchanged.set(noResult.get());
        return !unchanged.get();
    }

    @WrapWithCondition(method = "litServerTick", remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1937;method_8413(Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;I)V", remap = false))
    private static boolean minefed$sendOnlyChanges(class_1937 level, class_2338 pos, class_2680 oldState, class_2680 newState, int flags,
            @Share("unchanged") LocalBooleanRef unchanged) {
        boolean send = !unchanged.get();
        unchanged.set(false);
        return send;
    }
}
