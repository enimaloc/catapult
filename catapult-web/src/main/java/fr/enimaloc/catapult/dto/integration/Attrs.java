package fr.enimaloc.catapult.dto.integration;

/**
 * Attributes every node may carry: the id/classes channel-connections.js looks elements up by,
 * the SSE-driven state (null when the element never changes after render), and the data path
 * an editable element maps to ({@code field}, rendered as data-field).
 */
public record Attrs(String id, String className, SpaState state, String field) {

    public static final Attrs NONE = new Attrs(null, null, null, null);

    public static Attrs id(String id) {
        return NONE.withId(id);
    }

    public static Attrs cls(String className) {
        return NONE.withClass(className);
    }

    public static Attrs state(SpaState state) {
        return NONE.withState(state);
    }

    /** The dotted path of the payload data this element edits, rendered as data-field. */
    public static Attrs field(String field) {
        return NONE.withField(field);
    }

    public Attrs withId(String id) {
        return new Attrs(id, className, state, field);
    }

    public Attrs withClass(String className) {
        return new Attrs(id, this.className == null ? className : this.className + " " + className, state, field);
    }

    public Attrs withState(SpaState state) {
        return new Attrs(id, className, state, field);
    }

    public Attrs withField(String field) {
        return new Attrs(id, className, state, field);
    }

    /** Server-side only toggle, for elements whose JS flips {@code hidden} itself (OBS, Twitchat). */
    public Attrs hiddenIf(boolean hidden) {
        return hidden ? withClass("hidden") : this;
    }
}
