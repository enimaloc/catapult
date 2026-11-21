package fr.enimaloc.catapult.api.userapi;

/**
 * Builds the absolute "more" links of an answer: the caller's origin ({@code baseUrl}) followed by the detail route
 * of a kind of object in the given {@link GameInfoAssembler.LinkStyle}. The legacy style keeps the frozen
 * {@code /api/user/{kind}/{id}} links; the current one points at {@code /api/games/{kind}/{id}} and, for a binding,
 * {@code /api/me/bindings/{id}}. A route that only exists since version 3 gets {@link #SINCE_V3_QUERY}: the link
 * must work when opened as is (a browser, OBS or a {@code <video>} tag sends no {@code X-API-Version}, and the
 * default version may be 2).
 */
public record MoreLinks(String baseUrl, GameInfoAssembler.LinkStyle style) {

    /** The query that selects version 3 on a link opened without the version header. */
    public static final String SINCE_V3_QUERY = "?" + ApiV2.VERSION_PARAM + "=" + ApiV2.VERSION_3;

    /** The kinds of object a "more" link can point at. */
    public enum Kind {
        IGDB("igdb", false),
        STEAM("steam", false),
        XBOX("xbox", false),
        DTDD("dtdd", false),
        /** The channel's own binding: {@code /catapult/{id}} in the legacy style. */
        BINDING("catapult", false),
        /** HowLongToBeat, by IGDB id. */
        HLTB("hltb", true),
        /** ProtonDB, by Steam app id. */
        PROTONDB("protondb", true),
        /** SteamGridDB, by SteamGridDB game id. */
        STEAMGRIDDB("steamgriddb", true),
        /** Steam Deck compatibility, by Steam app id. */
        STEAMDECK("steamdeck", true),
        /** PCGamingWiki, by wiki page id. */
        PCGAMINGWIKI("pcgamingwiki", true);

        private final String segment;
        /** Whether the detail route only exists since version 3. */
        private final boolean sinceV3;

        Kind(String segment, boolean sinceV3) {
            this.segment = segment;
            this.sinceV3 = sinceV3;
        }
    }

    /** The absolute link to the detail route of the {@code kind} object named {@code id}. */
    public String to(Kind kind, Object id) {
        return baseUrl + switch (style) {
            case LEGACY -> ApiV2.PATH + "/" + kind.segment + "/" + id;
            case CURRENT -> kind == Kind.BINDING
                    ? ApiV2.ME_BINDINGS_PATH + "/" + id
                    : ApiV2.GAMES_PATH + "/" + kind.segment + "/" + id;
        } + (kind.sinceV3 ? SINCE_V3_QUERY : "");
    }
}
