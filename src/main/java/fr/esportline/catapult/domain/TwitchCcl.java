package fr.esportline.catapult.domain;

public enum TwitchCcl {
    /** Auto-set by Twitch based on game rating — not writable via the API. */
    MatureGame(false),
    ViolentGraphic(true),
    SexualThemes(true),
    LanguageBarrier(true),
    DrugUse(true),
    Gambling(true),
    ProfanityVulgarity(true);

    /** Whether the label can be set by the broadcaster via PATCH /helix/channels. */
    public final boolean editable;

    TwitchCcl(boolean editable) {
        this.editable = editable;
    }
}
