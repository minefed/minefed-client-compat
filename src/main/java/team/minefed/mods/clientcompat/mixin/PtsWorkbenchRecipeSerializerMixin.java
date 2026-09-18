/*
 * Copyright (c) 2026 Minefed
 * SPDX-License-Identifier: MIT
 */
package team.minefed.mods.clientcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Restores the reader to the unchanged PTS-Deco 4.0.0 server wire format. */
@Pseudo
@Mixin(
    targets = "com.nik123123555.ptsdeco.crafting.WorkbenchContructingRecipe$Serializer",
    remap = false
)
public abstract class PtsWorkbenchRecipeSerializerMixin {
    /**
     * PTS reads an unused notification flag after the result stack, although its
     * writer does not send that flag. Consuming it steals the next recipe's
     * identifier length (or reads past the end of the packet).
     *
     * Object with Coerce avoids a compile-time dependency on proprietary PTS or
     * Minecraft classes. All selectors are the Fabric 1.20.4 production names.
     */
    @Redirect(
        method = "fromNetwork(Lnet/minecraft/class_2540;)"
            + "Lcom/nik123123555/ptsdeco/crafting/WorkbenchContructingRecipe;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/class_2540;readBoolean()Z",
            remap = false
        ),
        remap = false,
        require = 1,
        expect = 1,
        allow = 1
    )
    private boolean minefed$skipAbsentNotification(@Coerce Object buffer) {
        return false;
    }
}
