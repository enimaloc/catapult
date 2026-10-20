package fr.enimaloc.catapult.assets;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Serves the third-party files {@link ExternalAssets} fetches, from catapult-web's own origin:
 * <ul>
 *     <li>{@code /assets/ext/fonts/material-icons.css}, Google Fonts' Material Icons style sheet;</li>
 *     <li>{@code /assets/ext/fonts/gstatic/s/{family}/{version}/{file}}, the fonts it names;</li>
 *     <li>{@code /assets/ext/twitchat/{commit}/{name}.svg}, Twitchat's icons (the Twitchat catalog's
 *     option pictures, see scripts/generate-twitchat-protocol.mjs).</li>
 * </ul>
 * Each path element is held to a strict pattern before anything is fetched. The type is catapult-web's,
 * never the upstream's, with nosniff; an SVG is also sandboxed, should it be opened on its own.
 */
@RestController
@RequestMapping("/assets/ext")
@RequiredArgsConstructor
public class ExternalAssetController {
    static final String SVG_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";
    private static final Map<String, MediaType> FONT_TYPES = Map.of(
            "woff2", MediaType.parseMediaType("font/woff2"),
            "woff", MediaType.parseMediaType("font/woff"),
            "ttf", MediaType.parseMediaType("font/ttf"));

    private final ExternalAssets assets;

    @GetMapping("/fonts/material-icons.css")
    public ResponseEntity<byte[]> materialIcons() {
        return serve(assets.materialIconsCss(), MediaType.parseMediaType("text/css;charset=UTF-8"), Duration.ofDays(1), null);
    }

    @GetMapping("/fonts/gstatic/s/{family:[a-z0-9]+}/{version:v[0-9]+}/{file:[A-Za-z0-9_-]+}.{ext:woff2|woff|ttf}")
    public ResponseEntity<byte[]> font(@PathVariable String family, @PathVariable String version,
                                       @PathVariable String file, @PathVariable String ext) {
        return serve(assets.font("s/" + family + "/" + version + "/" + file + "." + ext), FONT_TYPES.get(ext),
                Duration.ofDays(365), null);
    }

    @GetMapping("/twitchat/{commit:[0-9a-f]{40}}/{name:[A-Za-z0-9_-]+}.svg")
    public ResponseEntity<byte[]> twitchatIcon(@PathVariable String commit, @PathVariable String name) {
        return serve(assets.twitchatIcon(commit, name), MediaType.parseMediaType("image/svg+xml"),
                Duration.ofDays(365), SVG_CSP);
    }

    private static ResponseEntity<byte[]> serve(ExternalAssets.Result result, MediaType type, Duration maxAge, String csp) {
        if (result.status() != 200) return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(maxAge).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin");
        if (csp != null) response.header("Content-Security-Policy", csp);
        return response.body(result.body());
    }
}
