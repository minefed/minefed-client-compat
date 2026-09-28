/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.unlikepaladin.pfm.blocks.blockentities.PlateBlockEntity;
import net.minecraft.class_1799;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An empty plate draws nothing: renderStatic returns for empty stacks, and the
 * balanced pose push/pop, registry-key string and light lookup have no other
 * effect. Keep the renderer's public stack field as PFM would leave it.
 */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.entity.render.PlateBlockEntityRenderer", remap = false)
public abstract class PfmPlateRendererMixin {
    @Shadow(remap = false) public class_1799 itemStack;

    @Inject(method = "render(Lcom/unlikepaladin/pfm/blocks/blockentities/PlateBlockEntity;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$skipEmptyPlate(PlateBlockEntity plate, float tickDelta, class_4587 matrices, class_4597 buffers,
            int light, int overlay, CallbackInfo ci) {
        if (plate == null) return;
        class_1799 stack = plate.getItemInPlate();
        if (!stack.method_7960()) return;
        this.itemStack = stack;
        ci.cancel();
    }
}
