package fr.enimaloc.catapult.service.enrichment.trailer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The HLS URLs ffmpeg may be handed: {@code https} on Steam's or Microsoft's video CDNs only. The URL comes from a
 * store's answer, and ffmpeg opens whatever it is given ({@code file:}, {@code concat:}, any internal host), so
 * anything else is refused before it gets there.
 */
public final class TrailerHlsUrls {

    /** Steam's trailer CDNs ({@code video.akamai.steamstatic.com}, older {@code *.akamaihd.net} ones). */
    private static final List<String> STEAM_SUFFIXES = List.of(".steamstatic.com", ".akamaihd.net");
    /** The Microsoft Store's video CDN, as the display catalog's {@code CMSVideos[].HLS} gives it. */
    private static final Pattern MICROSOFT_HOST = Pattern.compile("cdn-dynmedia-\\d+\\.microsoft\\.com");

    private TrailerHlsUrls() {
    }

    /** {@code url} as a URI when ffmpeg may open it, else empty. */
    public static Optional<URI> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.strip());
            return isAllowed(uri) ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** True for an absolute {@code https} URL on an allowed host, with no user info and the default port. */
    public static boolean isAllowed(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return STEAM_SUFFIXES.stream().anyMatch(host::endsWith) || MICROSOFT_HOST.matcher(host).matches();
    }
}
