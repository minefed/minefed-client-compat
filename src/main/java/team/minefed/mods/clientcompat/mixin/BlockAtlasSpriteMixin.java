/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.class_1011;
import net.minecraft.class_2960;
import net.minecraft.class_7764;
import net.minecraft.class_7771;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import team.minefed.mods.clientcompat.atlas.AtlasSpriteOptimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Before the block atlas is stitched, stores each sprite as decided by {@link AtlasSpriteOptimizer}:
 * exact enlargements at their original size, oversized static sprites capped, and sprites whose size
 * would lower the whole atlas mipmap level aligned. Other atlases are not changed.
 */
@Mixin(targets = "net.minecraft.class_7766", remap = false)
public abstract class BlockAtlasSpriteMixin {
    private static final Logger MINEFED_LOGGER = LoggerFactory.getLogger("minefed-client-compat");

    @Shadow @Final private class_2960 field_40549;

    @ModifyVariable(method = "method_47663(Ljava/util/List;ILjava/util/concurrent/Executor;)Lnet/minecraft/class_7766$class_7767;",
        at = @At("HEAD"), argsOnly = true, remap = false, require = 1, allow = 1)
    private List<class_7764> minefed$optimizeBlockAtlasSprites(List<class_7764> sprites, @Local(argsOnly = true) int mipLevel) {
        if (!"minecraft".equals(field_40549.method_12836()) || !"textures/atlas/blocks.png".equals(field_40549.method_12832())) {
            return sprites;
        }
        final AtlasSpriteOptimizer.Settings settings = AtlasSpriteOptimizer.Settings.load();
        final List<class_7764> result = new ArrayList<>(sprites.size());
        long pixelsBefore = 0;
        long pixelsAfter = 0;
        int changed = 0;
        for (final class_7764 sprite : sprites) {
            final class_1011 image = ((SpriteContentsImageAccessor) (Object) sprite).minefed$getImage();
            AtlasSpriteOptimizer.Result optimized = null;
            if (image != null && image.method_4318() == class_1011.class_1012.field_4997) {
                try {
                    optimized = AtlasSpriteOptimizer.optimize(sprite.method_45816().method_12836(), image.method_4307(), image.method_4323(),
                        sprite.method_45807(), sprite.method_45815(), image::method_4315, mipLevel, settings);
                } catch (RuntimeException exception) {
                    MINEFED_LOGGER.warn("Keeping block atlas sprite {} unchanged", sprite.method_45816(), exception);
                }
            }
            if (optimized == null) {
                result.add(sprite);
                continue;
            }
            final class_1011 newImage = new class_1011(optimized.imageWidth, optimized.imageHeight, false);
            for (int y = 0; y < optimized.imageHeight; y++) {
                for (int x = 0; x < optimized.imageWidth; x++) {
                    newImage.method_4305(x, y, optimized.get(x, y));
                }
            }
            pixelsBefore += (long) sprite.method_45807() * sprite.method_45815();
            pixelsAfter += (long) optimized.frameWidth * optimized.frameHeight;
            result.add(new class_7764(sprite.method_45816(), new class_7771(optimized.frameWidth, optimized.frameHeight), newImage, sprite.method_52848()));
            // The original contents are no longer referenced; release their native image
            sprite.close();
            changed++;
        }
        if (changed > 0) {
            MINEFED_LOGGER.info("Block atlas: stored {} sprites at a new size ({} -> {} texels per frame)", changed, pixelsBefore, pixelsAfter);
        }
        return result;
    }
}
