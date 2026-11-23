package fr.enimaloc.catapult.service.debug;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** Names the external service a URL belongs to, for the recorded exchanges. */
@Component
public class ExternalServices {

    private final String gitlabHost;

    public ExternalServices(@Value("${gitlab.base-url:https://git.enimaloc.fr}") String gitlabBaseUrl) {
        this.gitlabHost = hostOf(gitlabBaseUrl);
    }

    /** The service name ({@code twitch}, {@code steam}, ...), or the host itself when it is not a known one. */
    public String of(URI uri) {
        // The browser's own sessions, recorded with a synthetic URL named after them
        String scheme = uri == null || uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("obs".equals(scheme) || "twitchat".equals(scheme)) {
            return scheme;
        }
        String host = uri == null || uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null) {
            return "unknown";
        }
        // Minecraft's Xbox Live sign-in steps come before the generic Xbox domains.
        if (host.equals("user.auth.xboxlive.com") || host.equals("xsts.auth.xboxlive.com")
                || host.equals("login.live.com") || host.equals("login.microsoftonline.com")
                || within(host, "minecraftservices.com") || within(host, "mojang.com")) {
            return "minecraft";
        }
        if (within(host, "twitch.tv")) {
            return "twitch";
        }
        if (within(host, "steampowered.com") || within(host, "steamcommunity.com")) {
            return "steam";
        }
        if (host.equals("api.igdb.com")) {
            return "igdb";
        }
        if (within(host, "xboxlive.com") || within(host, "xbox.com")) {
            return "xbox";
        }
        if (within(host, "epicgames.com") || within(host, "epicgames.dev")) {
            return "epic";
        }
        if (within(host, "battle.net") || within(host, "blizzard.com")) {
            return "battlenet";
        }
        if (within(host, "bungie.net")) {
            return "bungie";
        }
        if (within(host, "ppy.sh")) {
            return "osu";
        }
        if (within(host, "hypixel.net")) {
            return "hypixel";
        }
        if (within(host, "clashofclans.com") || within(host, "clashroyale.com") || within(host, "brawlstars.com")) {
            return "supercell";
        }
        if (within(host, "ankama.com")) {
            return "ankama";
        }
        if (within(host, "doesthedogdie.com")) {
            return "dtdd";
        }
        if (host.equals(gitlabHost)) {
            return "gitlab";
        }
        return host;
    }

    private static boolean within(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String hostOf(String url) {
        try {
            String host = url == null || url.isBlank() ? null : URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
