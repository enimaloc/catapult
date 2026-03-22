package fr.esportline.catapult.domain;

import java.util.Set;

public enum TwitchCcl {
    /** Auto-set by Twitch based on game rating — not writable via the API. */
    MatureGame(false),
    ViolentGraphic(true),
    SexualThemes(true),
    LanguageBarrier(true),
    DrugsIntoxication(true),
    Gambling(true),
    ProfanityVulgarity(true);

    /** Whether the label can be set by the broadcaster via PATCH /helix/channels. */
    public final boolean editable;

    TwitchCcl(boolean editable) {
        this.editable = editable;
    }

    // Keyword sets for matching content descriptor text (IGDB and Steam).
    // Match as substring of the descriptor string lowercased.
    public static final Set<String> KW_VIOLENCE  = Set.of("blood", "gore", "violence", "violent");
    public static final Set<String> KW_SEXUAL    = Set.of("nudity", "sexual", "sex", "suggestive", "erotic");
    public static final Set<String> KW_DRUGS     = Set.of("drug", "alcohol", "tobacco");
    public static final Set<String> KW_GAMBLING  = Set.of("gambling");
    public static final Set<String> KW_LANGUAGE  = Set.of("language", "lyrics", "profanity", "crude", "bad language");
}
