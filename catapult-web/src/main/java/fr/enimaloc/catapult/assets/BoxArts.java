package fr.enimaloc.catapult.assets;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.regex.Pattern;

/**
 * Where the pages load a Twitch category's box art from ({@code ${boxArts.url(b.boxArtUrl())}}, a model attribute
 * like {@link Avatars}): through {@link ExternalAssetController}, at the size it serves (whatever size the URL names:
 * Helix's search a small one, its games endpoint a {@code {width}x{height}} template). Any other URL is not shown (null): the
 * browser never asks Twitch's CDN. game-search.js does the same for the search results.
 */
@ControllerAdvice
public class BoxArts {
    private static final String PREFIX = ExternalAssets.TWITCH_CDN + "ttv-boxart/";
    private static final Pattern SIZE = Pattern.compile("-(\\{width}x\\{height}|\\d+x\\d+)(\\.\\w+)$");

    @ModelAttribute("boxArts")
    public BoxArts boxArts() {
        return this;
    }

    public String url(String boxArtUrl) {
        if (boxArtUrl == null || !boxArtUrl.startsWith(PREFIX)) return null;
        String file = SIZE.matcher(boxArtUrl.substring(PREFIX.length())).replaceFirst("-96x128$2");
        return ExternalAssets.BOX_ART_PATH.matcher("ttv-boxart/" + file).matches() ? "/assets/ext/boxart/" + file : null;
    }
}
