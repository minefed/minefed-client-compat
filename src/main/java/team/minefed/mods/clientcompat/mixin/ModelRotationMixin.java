/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import net.minecraft.class_789;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.minefed.mods.clientcompat.model.ModelRotationCache;

/** Retains BakedQuadFactory's original transform call and the remainder of quad baking. */
@Mixin(targets = "net.minecraft.class_796", remap = false)
public abstract class ModelRotationMixin {
    @Shadow(remap = false)
    private void method_3463(Vector3f vertex, class_789 rotation) {
        throw new AssertionError("Mixin shadow");
    }

    @Shadow(remap = false)
    private void method_3464(Vector3f vertex, Vector3f origin, Matrix4f matrix, Vector3f scale) {
        throw new AssertionError("Mixin shadow");
    }

    @Redirect(method = "method_3461([IILnet/minecraft/class_2350;Lnet/minecraft/class_787;[FLnet/minecraft/class_1058;Lnet/minecraft/class_4590;Lnet/minecraft/class_789;Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_796;method_3463(Lorg/joml/Vector3f;Lnet/minecraft/class_789;)V"),
        remap = false, require = 1, allow = 1)
    private void minefed$reuseStandardRotation(@Coerce Object factory, Vector3f vertex, class_789 rotation) {
        // The pinned call site invokes this factory. No CallbackInfo allocation on unrotated vertices.
        if (rotation == null) {
            method_3463(vertex, null);
            return;
        }
        ModelRotationCache.Rotation prepared = ModelRotationCache.find(rotation.comp_1119(), rotation.comp_1120(), rotation.comp_1121());
        if (prepared == null || !ModelRotationCache.supports(vertex, rotation.comp_1118())) {
            method_3463(vertex, rotation);
            return;
        }
        // The origin vector may be mutated by a model; capture it afresh, as vanilla does.
        method_3464(vertex, new Vector3f(rotation.comp_1118()), prepared.copyMatrix(), prepared.copyScale());
    }
}
