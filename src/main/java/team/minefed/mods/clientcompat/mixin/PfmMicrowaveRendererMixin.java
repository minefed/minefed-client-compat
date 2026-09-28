/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.unlikepaladin.pfm.blocks.blockentities.MicrowaveBlockEntity;
import net.minecraft.class_1799;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_8786;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An empty microwave draws nothing, so skip its light, recipe and pose work
 * (the pushes are balanced). While running, PFM looks up the same recipe up to
 * three times in one condition; nothing between those calls changes the recipe
 * manager or the input slot, so reuse the first result for that frame.
 */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.entity.render.MicrowaveBlockEntityRenderer", remap = false)
public abstract class PfmMicrowaveRendererMixin {
    @Shadow(remap = false) public class_1799 itemStack;

    @Inject(method = "render(Lcom/unlikepaladin/pfm/blocks/blockentities/MicrowaveBlockEntity;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$skipEmptyMicrowave(MicrowaveBlockEntity microwave, float tickDelta, class_4587 matrices, class_4597 buffers,
            int light, int overlay, CallbackInfo ci) {
        if (microwave == null) return;
        class_1799 stack = microwave.method_5438(0);
        if (!stack.method_7960()) return;
        this.itemStack = stack;
        ci.cancel();
    }

    @WrapOperation(method = "render(Lcom/unlikepaladin/pfm/blocks/blockentities/MicrowaveBlockEntity;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
        remap = false, require = 3, expect = 3, allow = 3,
        at = @At(value = "INVOKE", remap = false,
            target = "Lcom/unlikepaladin/pfm/blocks/blockentities/MicrowaveBlockEntity;getRecipe()Lnet/minecraft/class_8786;"))
    private class_8786<?> minefed$lookUpRecipeOnce(MicrowaveBlockEntity microwave, Operation<class_8786<?>> original,
            @Share("recipe") LocalRef<class_8786<?>> recipe, @Share("hasRecipe") LocalBooleanRef hasRecipe) {
        if (!hasRecipe.get()) {
            recipe.set(original.call(microwave));
            hasRecipe.set(true);
        }
        return recipe.get();
    }
}
