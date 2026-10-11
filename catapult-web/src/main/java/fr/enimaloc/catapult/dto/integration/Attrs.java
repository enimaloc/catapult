package fr.enimaloc.catapult.dto.integration;

/**
 * Attributes every node may carry: the id/classes channel-connections.js looks elements up by,
 * and the SSE-driven state (null when the element never changes after render).
 */
public record Attrs(String id, String className, SpaState state) {

    public static final Attrs NONE = new Attrs(null, null, null);

    public static Attrs id(String id) {
        return NONE.withId(id);
    }

    public static Attrs cls(String className) {
        return NONE.withClass(className);
    }

    public static Attrs state(SpaState state) {
        return NONE.withState(state);
    }

    public Attrs withId(String id) {
        return new Attrs(id, className, state);
    }

    public Attrs withClass(String className) {
        return new Attrs(id, this.className == null ? className : this.className + " " + className, state);
    }

    public Attrs withState(SpaState state) {
        return new Attrs(id, className, state);
    }

    /** Server-side only toggle, for elements whose JS flips {@code hidden} itself (OBS, Twitchat). */
    public Attrs hiddenIf(boolean hidden) {
        return hidden ? withClass("hidden") : this;
    }
}
