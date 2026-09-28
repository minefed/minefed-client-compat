/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Identify the optional Wikidata request as required by Wikimedia's API policy. */
@Pseudo
@Mixin(targets = "de.mrjulsen.mcdragonlib.util.Wikipedia$WikipediaArticle", remap = false)
public class WikipediaRequestMixin {
    @Redirect(method = "lambda$new$0(Ljava/lang/String;)V", remap = false,
        at = @At(value = "INVOKE", target = "Ljava/net/URL;openStream()Ljava/io/InputStream;", remap = false),
        require = 1, expect = 1, allow = 1)
    private InputStream minefed$identifiedWikipediaRequest(URL url) throws IOException {
        if (!"https".equals(url.getProtocol()) || !"www.wikidata.org".equals(url.getHost()))
            return url.openStream();
        URLConnection connection = url.openConnection();
        connection.setRequestProperty("User-Agent",
            "Minefed-Client-Compat/1.1.1 (https://github.com/minefed/minefed-client-compat)");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        return connection.getInputStream();
    }
}
