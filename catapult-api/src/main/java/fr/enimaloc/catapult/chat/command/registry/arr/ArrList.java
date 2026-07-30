package fr.enimaloc.catapult.chat.command.registry.arr;

import java.util.List;

/**
 * Coerces an {@code arr#*} function's first argument into a {@code List<Object>} — a list already
 * returned by another service function (e.g. {@code igdb#getGenres}) passes through as-is; the
 * DSL's universal "missing" value ({@code ""}) coerces to an empty list, letting {@code
 * arr#push("", "first")} start a new array from scratch (there's no array-literal syntax in this
 * DSL, only object literals). Anything else (a single scalar reached this argument by mistake)
 * degrades to a one-element list rather than throwing, matching every other function in this
 * registry's "never crash the whole command over one bad argument" convention.
 */
final class ArrList {

    private ArrList() {
    }

    @SuppressWarnings("unchecked")
    static List<Object> coerce(Object arg) {
        if (arg instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (arg == null || "".equals(arg)) {
            return List.of();
        }
        return List.of(arg);
    }
}
