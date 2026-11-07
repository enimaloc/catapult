package fr.enimaloc.catapult.domain.account;

/** What a personal API token may do: everything its member can, or only read ({@code GET /api/me/**}). */
public enum ApiTokenScope {
    FULL("cat_"),
    READ("catr_");

    private final String prefix;

    ApiTokenScope(String prefix) {
        this.prefix = prefix;
    }

    /** What every token of this scope starts with. */
    public String prefix() {
        return prefix;
    }
}
