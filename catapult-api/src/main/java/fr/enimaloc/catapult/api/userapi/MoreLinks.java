package fr.enimaloc.catapult.api.userapi;

/**
 * Builds the absolute "more" links of an answer: the caller's origin ({@code baseUrl}) followed by the detail route
 * of a kind of object in the given {@link GameInfoAssembler.LinkStyle}. The legacy style keeps the frozen
 * {@code /api/user/{kind}/{id}} links; the current one points at {@code /api/games/{kind}/{id}} and, for a binding,
 * {@code /api/me/bindings/{id}}.
 */
public record MoreLinks(String baseUrl, GameInfoAssembler.LinkStyle style) {

    /** The kinds of object a "more" link can point at. */
    public enum Kind {
        IGDB("igdb"),
        STEAM("steam"),
        XBOX("xbox"),
        DTDD("dtdd"),
        /** The channel's own binding: {@code /catapult/{id}} in the legacy style. */
        BINDING("catapult");

        private final String segment;

        Kind(String segment) {
            this.segment = segment;
        }
    }

    /** The absolute link to the detail route of the {@code kind} object named {@code id}. */
    public String to(Kind kind, Object id) {
        return baseUrl + switch (style) {
            case LEGACY -> ApiV2.PATH + "/" + kind.segment + "/" + id;
            case CURRENT -> kind == Kind.BINDING
                    ? ApiV2.ME_BINDINGS_PATH + "/" + id
                    : ApiV2.GAMES_PATH + "/" + kind.segment + "/" + id;
        };
    }
}
