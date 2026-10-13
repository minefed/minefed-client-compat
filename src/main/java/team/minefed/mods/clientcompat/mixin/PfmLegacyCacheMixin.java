/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import team.minefed.mods.clientcompat.pfm.PfmCacheFingerprint;

/** Normalizes only folder_hash values; version, loader and variant comparisons remain unchanged. */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.runtime.PFMGenerator$PFMCache", remap = false)
public abstract class PfmLegacyCacheMixin {
    @ModifyExpressionValue(method = "fromJson", at = @At(value = "INVOKE", ordinal = 4,
        target = "Lcom/google/gson/JsonElement;getAsString()Ljava/lang/String;"), require = 1, allow = 1, remap = false)
    private static String minefed$normalizeLegacyFingerprint(String fingerprint) {
        return PfmCacheFingerprint.normalizeLegacy(fingerprint);
    }
}
