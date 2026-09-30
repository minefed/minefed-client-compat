/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.cinemamod.mcef.MCEF;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MCEF 2.1.6 starts Chromium (browser, GPU and utility processes) as soon as the first screen opens,
 * even for players who never look at a web display. Defer that until something first uses the MCEF API:
 * every API entry point that needs Chromium calls {@code assertInitialized()} first. The native download
 * at startup is unchanged. The first web display appears after Chromium has started (about 1-2 s).
 */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEF", remap = false)
public abstract class McefLazyInitMixin {
    @Unique private static boolean minefed$requested;
    @Unique private static boolean minefed$initializing;

    @Inject(method = "initialize()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void minefed$deferUntilUsed(CallbackInfoReturnable<Boolean> callback) {
        if (!minefed$requested) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "assertInitialized()V", at = @At("HEAD"), remap = false, require = 1)
    private static void minefed$initializeOnFirstUse(CallbackInfo callback) {
        if (!minefed$initializing && !MCEF.isInitialized()) {
            minefed$requested = true;
            minefed$initializing = true;
            try {
                MCEF.initialize();
            } finally {
                minefed$initializing = false;
            }
        }
    }
}
