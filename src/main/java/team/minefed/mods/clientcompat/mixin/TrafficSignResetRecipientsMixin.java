/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.class_1923;
import net.minecraft.class_1937;
import net.minecraft.class_2586;
import net.minecraft.class_3218;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A new sign texture sends TrafficSignTextureResetPacket to every player in the dimension.
 * Its client handler only resets the TrafficSignBlockEntity it finds at that position, so it
 * does nothing on clients that were not sent this chunk. Send it only to the players that
 * vanilla sends this chunk's block-entity updates to. Other levels keep the full list.
 */
@Pseudo
@Mixin(targets = "de.mrjulsen.trafficcraft.block.entity.TrafficSignBlockEntity", remap = false)
public abstract class TrafficSignResetRecipientsMixin {
    @WrapOperation(method = "setAndResetTexture(Lde/mrjulsen/trafficcraft/data/NamedTrafficSignTextureReference;)V",
        remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1937;method_18456()Ljava/util/List;", remap = false))
    private List<?> minefed$playersWithChunk(class_1937 level, Operation<List<?>> original) {
        if (!(level instanceof class_3218 server)) return original.call(level);
        class_2586 entity = (class_2586) (Object) this;
        return server.method_14178().field_17254.method_17210(new class_1923(entity.method_11016()), false);
    }
}
