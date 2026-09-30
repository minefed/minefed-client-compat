/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import net.minecraft.class_1011;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.class_7764", remap = false)
public interface SpriteContentsImageAccessor {
    @Accessor(value = "field_40539", remap = false)
    class_1011 minefed$getImage();
}
