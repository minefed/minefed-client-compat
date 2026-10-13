/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.google.gson.Gson;
import java.io.Reader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.minefed.mods.clientcompat.scena.ScenaAbsentLoaderCache;

@Pseudo
@Mixin(targets = "com.communi.suggestu.scena.fabric.platform.client.rendering.model.loader.FabricPlatformModelLoaderPlatformDelegate", remap = false)
public abstract class ScenaModelLoaderMixin {
    @Redirect(method = "loadModelResource", at = @At(value = "INVOKE",
            target = "Lcom/google/gson/Gson;fromJson(Ljava/io/Reader;Ljava/lang/Class;)Ljava/lang/Object;"), require = 1, allow = 1)
    private Object minefed$reuseAbsentLoader(Gson gson, Reader reader, Class<?> type) {
        return ScenaAbsentLoaderCache.read(gson, reader, type);
    }
}
