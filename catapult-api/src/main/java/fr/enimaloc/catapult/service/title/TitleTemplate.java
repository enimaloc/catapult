package fr.enimaloc.catapult.service.title;

import java.util.Optional;
import java.util.Set;

/**
 * A stream title template rendered: its variables ({@code {{GAME}}}…) replaced and, with conditions on, its
 * {@code {{COND ? [[if true]] : [[if false]]}}} resolved — {@code !}, {@code &&} before {@code ||}, parentheses, and
 * ternaries nested in a branch. Anything that isn't exactly one of them stays as typed: a ternary that doesn't read
 * through is kept raw, up to its matching {@code }}}. With conditions off, only the variables are replaced, as before.
 */
public final class TitleTemplate {

    public static final Set<String> VARIABLES =
            Set.of("GAME", "CATEGORY", "TITLE", "ORIGINAL_TITLE", "DROP_NAME", "DROP_END");
    public static final Set<String> CONDITIONS =
            Set.of("DROP_ENABLED", "HAS_GAME", "IS_CUSTOM", "IS_INCOMPLETE", "IS_NO_GAME", "HAS_TW", "IS_MATURE");
    /** Twitch refuses a longer title. */
    public static final int MAX_LENGTH = 140;

    private TitleTemplate() {
    }

    /**
     * {@code template} rendered, trimmed and cut to Twitch's limit; empty when blank, or when a chosen part uses a
     * title that couldn't be read (better left alone than half-rewritten).
     */
    public static Optional<String> render(String template, TitleContext context, boolean conditions) {
        if (template == null || template.isBlank()) return Optional.empty();
        String result;
        try {
            result = expand(template, context, conditions).strip();
        } catch (UnreadableTitle e) {
            return Optional.empty();
        }
        if (result.codePointCount(0, result.length()) > MAX_LENGTH) {
            result = result.substring(0, result.offsetByCodePoints(0, MAX_LENGTH)).strip();
        }
        // Twitch refuses an empty title
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private static String expand(String text, TitleContext context, boolean conditions) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            int close = text.indexOf("}}", open + 2);
            String name = close < 0 ? null : text.substring(open + 2, close);
            if (name != null && VARIABLES.contains(name)) {
                out.append(value(name, context));
                i = close + 2;
            } else if (conditions) {
                Ternary ternary = Ternary.read(text, open, context);
                if (ternary != null) {
                    out.append(expand(ternary.chosen(), context, true));
                    i = ternary.end();
                } else {
                    int end = matchingClose(text, open);
                    out.append(text, open, end);
                    i = end;
                }
            } else {
                out.append("{{");
                i = open + 2;
            }
        }
        return out.toString();
    }

    private static String value(String name, TitleContext context) {
        return switch (name) {
            case "GAME" -> orEmpty(context.game());
            case "CATEGORY" -> orEmpty(context.category());
            case "DROP_NAME" -> orEmpty(context.dropName());
            case "DROP_END" -> orEmpty(context.dropEnd());
            case "TITLE" -> context.title().get().orElseThrow(UnreadableTitle::new);
            default -> context.originalTitle().get().orElseThrow(UnreadableTitle::new);
        };
    }

    /** The end (exclusive) of the {@code {{…}}} opened at {@code open}, nested pairs counted; the text's end when none. */
    private static int matchingClose(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length() - 1) {
            if (text.startsWith("{{", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith("}}", i)) {
                depth--;
                i += 2;
                if (depth == 0) return i;
            } else {
                i++;
            }
        }
        return text.length();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** A title a chosen part needs couldn't be read. */
    private static final class UnreadableTitle extends RuntimeException {
        UnreadableTitle() {
            super(null, null, false, false);
        }
    }

    /** {@code {{COND ? [[a]] : [[b]]}}} read from {@code open}: the chosen branch's raw text and where it ends. */
    private record Ternary(String chosen, int end) {

        static Ternary read(String text, int open, TitleContext context) {
            int question = text.indexOf('?', open + 2);
            if (question < 0) return null;
            Boolean condition = new Condition(text.substring(open + 2, question), context).parse();
            if (condition == null) return null;
            int[] whenTrue = branch(text, skipSpaces(text, question + 1));
            if (whenTrue == null) return null;
            int colon = skipSpaces(text, whenTrue[1]);
            if (colon >= text.length() || text.charAt(colon) != ':') return null;
            int[] whenFalse = branch(text, skipSpaces(text, colon + 1));
            if (whenFalse == null) return null;
            int close = skipSpaces(text, whenFalse[1]);
            if (!text.startsWith("}}", close)) return null;
            int[] chosen = condition ? whenTrue : whenFalse;
            return new Ternary(text.substring(chosen[0], chosen[1] - 2), close + 2);
        }

        /**
         * {@code [[…]]} at {@code at}, nested pairs counted, a run of {@code ]} closing on its last two: {start of its
         * text, end after its {@code ]]}}, or null.
         */
        private static int[] branch(String text, int at) {
            if (!text.startsWith("[[", at)) return null;
            int depth = 1;
            int i = at + 2;
            while (i < text.length()) {
                if (text.startsWith("[[", i)) {
                    depth++;
                    i += 2;
                } else if (text.startsWith("]]", i)) {
                    depth--;
                    i += 2;
                    if (depth == 0) {
                        // A run of ] closes on its last two: "[[ [Drops]]]" is the branch " [Drops]"
                        while (i < text.length() && text.charAt(i) == ']') i++;
                        return new int[]{at + 2, i};
                    }
                } else {
                    i++;
                }
            }
            return null;
        }

        private static int skipSpaces(String text, int i) {
            while (i < text.length() && text.charAt(i) == ' ') i++;
            return i;
        }
    }

    /** A condition: {@code or := and ("||" and)*}, {@code and := not ("&&" not)*}, {@code not := "!" not | atom}. */
    private static final class Condition {
        private final String text;
        private final TitleContext context;
        private int at;

        Condition(String text, TitleContext context) {
            this.text = text;
            this.context = context;
        }

        /** Its value; null when it doesn't read through or names an unknown condition. */
        Boolean parse() {
            Boolean value = or();
            spaces();
            return value != null && at == text.length() ? value : null;
        }

        private Boolean or() {
            Boolean value = and();
            while (value != null && take("||")) {
                Boolean right = and();
                value = right == null ? null : value || right;
            }
            return value;
        }

        private Boolean and() {
            Boolean value = not();
            while (value != null && take("&&")) {
                Boolean right = not();
                value = right == null ? null : value && right;
            }
            return value;
        }

        private Boolean not() {
            if (take("!")) {
                Boolean value = not();
                return value == null ? null : !value;
            }
            if (take("(")) {
                Boolean value = or();
                return value != null && take(")") ? value : null;
            }
            spaces();
            int start = at;
            while (at < text.length() && (Character.isUpperCase(text.charAt(at)) || text.charAt(at) == '_')) at++;
            String name = text.substring(start, at);
            if (!CONDITIONS.contains(name)) return null;
            return Boolean.TRUE.equals(context.conditions().get(name));
        }

        private boolean take(String token) {
            spaces();
            if (!text.startsWith(token, at)) return false;
            at += token.length();
            return true;
        }

        private void spaces() {
            while (at < text.length() && text.charAt(at) == ' ') at++;
        }
    }
}
