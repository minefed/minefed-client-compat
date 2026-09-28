/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.unlikepaladin.pfm.blocks.TrashcanBlock;
import com.unlikepaladin.pfm.blocks.blockentities.TrashcanBlockEntity;
import net.minecraft.class_1799;
import net.minecraft.class_1920;
import net.minecraft.class_2338;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PFM draws all nine trash can slots, even empty ones that renderStatic skips,
 * and reads the light above the can once per slot. Stop early when every slot
 * is empty, leaving the public stack field as PFM's loop would. Otherwise read
 * the light once per call: the position is fixed and client light, block state
 * and chunk data only change on this render thread between frames.
 */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.entity.render.TrashcanBlockEntityRenderer", remap = false)
public abstract class PfmTrashcanRendererMixin {
    @Shadow(remap = false) public class_1799 itemStack;

    @Inject(method = "render(Lcom/unlikepaladin/pfm/blocks/blockentities/TrashcanBlockEntity;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$skipEmptyTrashcan(TrashcanBlockEntity trashcan, float tickDelta, class_4587 matrices, class_4597 buffers,
            int light, int overlay, CallbackInfo ci) {
        if (trashcan == null) return;
        for (int slot = 0; slot < 9; slot++) {
            if (!trashcan.method_5438(slot).method_7960()) return;
        }
        if (!(trashcan.method_11010().method_26204() instanceof TrashcanBlock)) this.itemStack = trashcan.method_5438(8);
        ci.cancel();
    }

    @WrapOperation(method = "render(Lcom/unlikepaladin/pfm/blocks/blockentities/TrashcanBlockEntity;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
        remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_761;method_23794(Lnet/minecraft/class_1920;Lnet/minecraft/class_2338;)I", remap = false))
    private int minefed$readLightOnce(class_1920 level, class_2338 pos, Operation<Integer> original,
            @Share("light") LocalIntRef light, @Share("hasLight") LocalBooleanRef hasLight) {
        if (!hasLight.get()) {
            light.set(original.call(level, pos));
            hasLight.set(true);
        }
        return light.get();
    }
}
