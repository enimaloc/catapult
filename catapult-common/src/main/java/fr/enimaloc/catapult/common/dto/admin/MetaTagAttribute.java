package fr.enimaloc.catapult.common.dto.admin;

/**
 * The attribute a configured meta tag is named by: {@code <meta name="…">} (verifications,
 * theme-color…) or {@code <meta property="…">} (Open Graph). No {@code http-equiv}: a refresh or a
 * Content-Security-Policy set from the admin page would act on every page of the site.
 */
public enum MetaTagAttribute {
    NAME("name"),
    PROPERTY("property");

    private final String html;

    MetaTagAttribute(String html) {
        this.html = html;
    }

    /** The attribute as written in the tag: {@code name} or {@code property}. */
    public String html() {
        return html;
    }
}
