/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import de.mrjulsen.mcdragonlib.client.ber.BERGraphics;
import de.mrjulsen.trafficcraft.client.TrafficLightTextureManager.TrafficLightTextureKey;
import java.util.Optional;
import net.minecraft.class_2586;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.minefed.mods.clientcompat.trafficcraft.BulbModelCache;

/**
 * Every bulb of every traffic light searches TrafficCraft's model list with a stream on each
 * frame. Remember the model that search returns for each icon/color pair and draw later
 * bulbs with that same model object through TrafficCraft's own render method.
 */
@Pseudo
@Mixin(targets = "de.mrjulsen.trafficcraft.client.TrafficLightTextureManager", remap = false)
public abstract class TrafficLightTextureLookupMixin {
    @Inject(method = "render(Lde/mrjulsen/mcdragonlib/client/ber/BERGraphics;Lnet/minecraft/class_2586;Lde/mrjulsen/trafficcraft/client/TrafficLightTextureManager$TrafficLightTextureKey;I)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 1, expect = 1, allow = 1)
    private static void minefed$renderKnownModel(BERGraphics<?> graphics, class_2586 blockEntity, TrafficLightTextureKey key, int light,
            CallbackInfo ci) {
        Object model = BulbModelCache.get(key);
        if (model == null) return;
        ((TrafficLightBulbModelInvoker) model).minefed$render(graphics, blockEntity, light);
        ci.cancel();
    }

    @WrapOperation(method = "render(Lde/mrjulsen/mcdragonlib/client/ber/BERGraphics;Lnet/minecraft/class_2586;Lde/mrjulsen/trafficcraft/client/TrafficLightTextureManager$TrafficLightTextureKey;I)V",
        remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;", remap = false))
    private static Object minefed$rememberModel(Optional<?> match, Object fallback, Operation<Object> original,
            @Local(argsOnly = true) TrafficLightTextureKey key) {
        return BulbModelCache.put(key, original.call(match, fallback));
    }
}
