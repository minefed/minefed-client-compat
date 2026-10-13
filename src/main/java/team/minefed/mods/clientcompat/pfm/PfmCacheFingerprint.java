/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.pfm;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Compact cache fingerprints, with in-memory migration of PFM's original full-content hex. */
public final class PfmCacheFingerprint {
    private static final String PREFIX = "minefed-sha256-v1:";
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    private PfmCacheFingerprint() {}

    public static byte[] readDigest(InputStream input) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = BUFFER.get();
        boolean empty = true;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            digest.update(buffer, 0, count);
            empty = false;
        }
        // The upstream HashCode.fromBytes rejects empty files; preserve that behavior.
        return empty ? new byte[0] : digest.digest();
    }

    public static String tagged(String digestHex) {
        return PREFIX + digestHex;
    }

    public static String normalizeLegacy(String value) {
        if (value.startsWith(PREFIX)) return value;
        // Keep malformed caches unequal to a valid fingerprint, rather than failing the reload.
        if (value.isEmpty() || (value.length() & 1) != 0) return value;
        try {
            return tagged(HexFormat.of().formatHex(digest().digest(HexFormat.of().parseHex(value))));
        } catch (IllegalArgumentException invalidHex) {
            return value;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
