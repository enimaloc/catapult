package fr.enimaloc.catapult.dto.index;

import lombok.Value;

/**
 * A page the client-side router (spa.js) can render in place: {@code id} is its path, and
 * {@code templateUrl} the endpoint returning its {@code template :: fragment}. A {@code dynamic}
 * page also takes one trailing path segment (e.g. /channel/{username}).
 *
 * <p>Deliberately a getter-style class rather than a record: index.html inlines the page list
 * as JSON for spa.js, and Thymeleaf's own JavaScript serializer (no Jackson 2 on this classpath)
 * only sees JavaBean getters — a record would come out as {@code {}}.
 */
@Value
public class SpaPage {
    String id;
    String template;
    String fragment;
    String templateUrl;
    String titleKey;
    boolean dynamic;

    public SpaPage(String id, String template, String fragment, String templateUrl, String titleKey,
                   boolean dynamic) {
        this.id = id;
        this.template = template;
        this.fragment = fragment;
        this.templateUrl = templateUrl;
        this.titleKey = titleKey;
        this.dynamic = dynamic;
    }

    public SpaPage(String id, String template, String fragment, String templateUrl, String titleKey) {
        this(id, template, fragment, templateUrl, titleKey, false);
    }
}
