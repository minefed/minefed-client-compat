/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.class_2680;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.minefed.mods.clientcompat.model.ModelVariantIndex;

/** Keeps vanilla parsing, final predicate evaluation, model registration and overlap errors. */
@Mixin(targets = "net.minecraft.class_1088", remap = false)
public abstract class ModelVariantIndexMixin {
    @Unique private static final ThreadLocal<ModelVariantIndex<class_2680>> MINEFED_VARIANT_INDEX =
        ThreadLocal.withInitial(() -> new ModelVariantIndex<>(state ->
            state.getClass() == class_2680.class ? state.method_11656() : null));

    @Inject(method = "method_4725", at = @At("RETURN"), cancellable = true,
        remap = false, require = 1, expect = 1, allow = 1)
    private static void minefed$preserveParsedConditions(CallbackInfoReturnable<Predicate<class_2680>> callback,
            @Local Map<?, ?> conditions) {
        callback.setReturnValue(ModelVariantIndex.preserve(callback.getReturnValue(), conditions));
    }

    @Redirect(method = "method_4731", remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;", remap = false))
    private static Stream<class_2680> minefed$filterMatchingStates(Stream<class_2680> original,
            Predicate<? super class_2680> predicate, @Local(argsOnly = true) ImmutableList<class_2680> states) {
        return MINEFED_VARIANT_INDEX.get().filter(original, states, predicate);
    }
}
