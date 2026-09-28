/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Upload a new herringbone sprite before its first draw. PFM otherwise leaves
 * a purple placeholder until END_CLIENT_TICK. Keep that queue intact so its
 * normal batch refresh still persists the asset cache and updates world meshes.
 * Running the full refresh here would rebuild Sodium workers for every icon.
 * Reflection avoids a build dependency on PFM; all Minecraft selectors are
 * pinned Fabric 1.20.4 intermediary names, verified by the probes and live game.
 */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.runtime.TextureReloadQueue", remap = false)
public abstract class PfmImmediateTextureMixin {
    @Inject(method = "requestReload(Lnet/minecraft/class_2960;)V", at = @At("TAIL"), remap = false, require = 1, expect = 1, allow = 1)
    private static void minefed$uploadFirstHerringboneFrame(@Coerce Object id, CallbackInfo ci) {
        if (id == null || !id.toString().startsWith("pfm:block/")
                || !id.toString().endsWith("_herringbone_planks")) return;
        try {
            Class<?> render = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            if (!(boolean) render.getMethod("isOnRenderThread").invoke(null)) return;
            Class<?> gl = Class.forName("org.lwjgl.opengl.GL11");
            int previous = (int) gl.getMethod("glGetInteger", int.class).invoke(null, 0x8069);
            try {
                Class<?> minecraft = Class.forName("net.minecraft.class_310");
                Object client = minecraft.getMethod("method_1551").invoke(null);
                Object resources = minecraft.getMethod("method_1478").invoke(client);
                Object textures = minecraft.getMethod("method_1531").invoke(client);
                Class<?> atlasType = Class.forName("net.minecraft.class_1059");
                Class<?> identifier = Class.forName("net.minecraft.class_2960");
                Object atlas = Class.forName("net.minecraft.class_1060")
                    .getMethod("method_4619", identifier)
                    .invoke(textures, atlasType.getField("field_5275").get(null));
                atlasType.getMethod("method_23207").invoke(atlas);
                Method upload = Class.forName("com.unlikepaladin.pfm.runtime.TextureReloadQueue")
                    .getDeclaredMethod("reloadSingleSprite", Class.forName("net.minecraft.class_3300"), atlasType, identifier);
                upload.setAccessible(true);
                upload.invoke(null, resources, atlas, id);
            } finally {
                render.getMethod("bindTexture", int.class).invoke(null, previous);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot upload PFM's first herringbone frame", e);
        }
    }
}
