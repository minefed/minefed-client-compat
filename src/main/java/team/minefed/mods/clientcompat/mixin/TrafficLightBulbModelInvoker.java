/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import de.mrjulsen.mcdragonlib.client.ber.BERGraphics;
import net.minecraft.class_2586;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls TrafficCraft's own bulb drawing for a model found by {@link TrafficLightTextureLookupMixin}. */
@Mixin(targets = "de.mrjulsen.trafficcraft.client.TrafficLightTextureManager$TrafficLightBulbModel", remap = false)
public interface TrafficLightBulbModelInvoker {
    @Invoker(value = "render", remap = false)
    void minefed$render(BERGraphics<?> graphics, class_2586 blockEntity, int light);
}
