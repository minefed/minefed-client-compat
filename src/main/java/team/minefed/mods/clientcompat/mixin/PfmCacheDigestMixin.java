/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.io.FileInputStream;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.minefed.mods.clientcompat.pfm.PfmCacheFingerprint;

/** Keeps PFM's traversal, exclusions and error handling; hashes streamed bytes instead of storing them. */
@Pseudo
@Mixin(targets = "com.unlikepaladin.pfm.runtime.PFMGenerator", remap = false)
public abstract class PfmCacheDigestMixin {
    @Redirect(method = "collectFiles", at = @At(value = "INVOKE",
        target = "Ljava/io/FileInputStream;readAllBytes()[B"), require = 1, allow = 1, remap = false)
    private static byte[] minefed$streamFingerprint(FileInputStream stream) throws IOException {
        return PfmCacheFingerprint.readDigest(stream);
    }

    @ModifyExpressionValue(method = "collectFiles", at = @At(value = "INVOKE",
        target = "Lcom/google/common/hash/HashCode;toString()Ljava/lang/String;"), require = 1, allow = 1, remap = false)
    private static String minefed$tagFingerprint(String digest) {
        return PfmCacheFingerprint.tagged(digest);
    }
}
