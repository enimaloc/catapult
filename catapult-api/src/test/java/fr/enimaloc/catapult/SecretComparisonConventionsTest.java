package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A secret received from the outside (a token, a state, a nonce, a signature, a key, a code…) is compared in constant
 * time with {@code MessageDigest.isEqual}, never with {@code equals} or {@code ==}, which stop at the first different
 * character and leak how much of a guess was right. Read from the sources as text. The comparisons of values that
 * never came from a caller are listed in {@link #KEPT} with the reason.
 */
class SecretComparisonConventionsTest {

    /** One comparison: {@code path} is relative to the repository root, {@code text} is the line, normalized. */
    record Hit(String path, int line, String text) {

        String key() {
            return path + ":" + text;
        }
    }

    private static final String API = "catapult-api/src/main/java/fr/enimaloc/catapult/";
    private static final String COMMON = "catapult-common/src/main/java/fr/enimaloc/catapult/common/";
    private static final String WEB = "catapult-web/src/main/java/fr/enimaloc/catapult/";

    /** The comparisons of values that are not received secrets: {@code "<path>:<line>"} → why. */
    static final Map<String, String> KEPT = Map.ofEntries(
            Map.entry(
                    API + "service/igdb/IgdbClient.java:if (!token.equals(currentToken)) {",
                    "IGDB's own OAuth token against the one already set on the wrapper; nothing a caller sends"),
            Map.entry(
                    API + "service/tw/TwLabelService.java:if (message != null && !message.equals(code)) {",
                    "a message key against the message a lookup echoed back; the code is not a secret"),
            Map.entry(
                    API + "service/steam/SteamLanguage.java:if (value.getCode().equals(locale.getLanguage()) "
                            + "&& value.getAdditionalPredicate().test(locale)) {",
                    "a language code against a locale; the code is not a secret"),
            Map.entry(
                    COMMON + "error/ErrorCode.java:return Arrays.stream(values()).filter(c -> c.code.equals(code))"
                            + ".findFirst();",
                    "an API error code looked up in the enum; the code is public"),
            Map.entry(
                    WEB + "controller/NavEventStreamController.java:if (current == null "
                            + "|| !Objects.equals(current.account(), account) "
                            + "|| !Objects.equals(current.token(), token)) {",
                    "change detection of the login the session already holds, not a check of a received secret"),
            Map.entry(
                    WEB + "controller/NavEventStreamController.java:boolean same = token != null "
                            + "&& token.equals(nav.token());",
                    "change detection of the login the session already holds, not a check of a received secret"),
            Map.entry(
                    WEB + "service/mock/MockApiService.java:return data.values().stream()"
                            + ".filter(d -> token.equals(d.getWidgetToken())).findFirst();",
                    "the mock's in-memory widget lookup, dev only and without a real secret"),
            Map.entry(
                    WEB + "service/mock/MockData.java:while (previous.equals(inviteCode)) inviteCode = "
                            + "newInviteCode();",
                    "the mock draws a new invite code until it differs from the previous one"));

    private static final Path ROOT = Path.of("..");
    private static final List<String> MODULES = List.of("catapult-api", "catapult-common", "catapult-web");
    /** Main sources only, unlike EnglishConventionsTest. Fewer files than this under a prefix means a tree went missing, not that it got compliant. */
    private static final Map<String, Integer> MIN_FILES =
            Map.of("catapult-api/", 600, "catapult-common/", 150, "catapult-web/", 150);

    // ---------------------------------------------------------------------------------------------- detector

    /** The words of a name that make it a secret. {@code api} followed by {@code key} is one too. */
    private static final Set<String> SECRET_WORDS =
            Set.of("token", "secret", "state", "nonce", "signature", "hash", "ticket", "apikey", "password", "code");
    /** Names that hold a secret word and are not a secret: a number, a status or a hash of the object itself. */
    private static final Set<String> NOT_SECRETS = Set.of("hashcode", "statuscode", "errorcode", "exitcode");

    private static final Pattern EQUALS_CALL = Pattern.compile("\\.\\s*equals(?:IgnoreCase)?\\s*\\(");
    private static final Pattern OBJECTS_EQUALS = Pattern.compile("\\bObjects\\s*\\.\\s*equals\\s*\\(");
    private static final Pattern OPERATOR = Pattern.compile("(?<![=!<>])[=!]=(?!=)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern WORD = Pattern.compile("\\p{Lu}?\\p{Ll}+|\\p{Lu}+(?!\\p{Ll})|\\d+");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern NOT_A_VALUE = Pattern.compile("null|true|false|this|\\d[\\w.]*|'x*'|\"x*\"");

    /** Every comparison of a secret in {@code source}, one per line. */
    static List<Hit> violations(String path, String source) {
        char[] blanked = blank(source);
        String code = new String(blanked);
        Set<Integer> offsets = new LinkedHashSet<>();

        Matcher objects = OBJECTS_EQUALS.matcher(code);
        while (objects.find()) {
            int open = objects.end() - 1;
            int comma = topLevelComma(code, open + 1);
            int close = closing(code, open);
            if (comma < 0 || comma > close) {
                continue;
            }
            String first = code.substring(open + 1, comma);
            String second = code.substring(comma + 1, close);
            if (isValue(first) && isValue(second) && (isSecret(first) || isSecret(second))) {
                offsets.add(objects.start());
            }
        }
        Matcher equals = EQUALS_CALL.matcher(code);
        while (equals.find()) {
            if (code.substring(0, equals.start()).stripTrailing().endsWith("Objects")) {
                continue;
            }
            int open = equals.end() - 1;
            String receiver = code.substring(operandStart(code, equals.start()), equals.start());
            String argument = code.substring(open + 1, closing(code, open));
            if (isValue(receiver) && isValue(argument) && (isSecret(receiver) || isSecret(argument))) {
                offsets.add(equals.start());
            }
        }
        Matcher operator = OPERATOR.matcher(code);
        while (operator.find()) {
            String left = code.substring(operandStart(code, operator.start()), operator.start());
            String right = code.substring(operator.end(), operandEnd(code, operator.end()));
            if (isValue(left) && isValue(right) && (isSecret(left) || isSecret(right))) {
                offsets.add(operator.start());
            }
        }

        List<String> lines = List.of(source.split("\n", -1));
        List<Hit> hits = new ArrayList<>();
        Set<Integer> seen = new TreeSet<>();
        for (int offset : offsets) {
            int line = 1 + (int) source.substring(0, offset).chars().filter(c -> c == '\n').count();
            if (seen.add(line)) {
                hits.add(new Hit(path, line, lines.get(line - 1).strip().replaceAll("\\s+", " ")));
            }
        }
        return hits;
    }

    /**
     * Whether the operand is a value a caller could have sent: not {@code null}, a number, a boolean, a literal or
     * a constant such as an enum value.
     */
    private static boolean isValue(String operand) {
        String bare = operand.strip();
        if (bare.isEmpty() || NOT_A_VALUE.matcher(bare).matches()) {
            return false;
        }
        String last = bare.substring(bare.lastIndexOf('.') + 1);
        return !CONSTANT.matcher(last).matches();
    }

    /** Whether the last name of the operand, a variable, a field or a call, is built from a secret word. */
    private static boolean isSecret(String operand) {
        String last = null;
        Matcher identifier = IDENTIFIER.matcher(withoutArguments(operand));
        while (identifier.find()) {
            last = identifier.group();
        }
        if (last == null || NOT_SECRETS.contains(last.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String previous = "";
        Matcher word = WORD.matcher(last);
        while (word.find()) {
            String lower = word.group().toLowerCase(Locale.ROOT);
            if (SECRET_WORDS.contains(lower) || (previous.equals("api") && lower.equals("key"))) {
                return true;
            }
            previous = lower;
        }
        return false;
    }

    /** The operand with the content of its parentheses removed, so a call is read by its name. */
    private static String withoutArguments(String operand) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : operand.toCharArray()) {
            if (c == '(' || c == '[') {
                depth++;
                out.append(c);
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
                out.append(c);
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The start of the operand that ends at {@code end}: a chain of names, calls, indexes and casts. */
    private static int operandStart(String code, int end) {
        int i = end;
        while (i > 0 && Character.isWhitespace(code.charAt(i - 1))) {
            i--;
        }
        int depth = 0;
        while (i > 0) {
            char c = code.charAt(i - 1);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0
                    && !(Character.isJavaIdentifierPart(c) || c == '.' || c == '"' || c == '\'')) {
                if (Character.isWhitespace(c) && i > 1 && code.charAt(i - 2) == '.') {
                    i--;
                    continue;
                }
                break;
            }
            i--;
        }
        return i;
    }

    /** The end of the operand that starts at {@code start}. */
    private static int operandEnd(String code, int start) {
        int i = start;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        if (i < code.length() && code.charAt(i) == '!') {
            i++;
        }
        int depth = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0
                    && !(Character.isJavaIdentifierPart(c) || c == '.' || c == '"' || c == '\'')) {
                break;
            }
            i++;
        }
        return i;
    }

    /** The offset of the {@code )} closing the {@code (} at {@code open}. */
    private static int closing(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '(') {
                depth++;
            } else if (code.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /** The offset of the first {@code ,} outside any parenthesis from {@code from}, or -1. */
    private static int topLevelComma(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth-- == 0) {
                    return -1;
                }
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The source with every comment blanked and the content of every literal filled with {@code x}, newlines kept. */
    static char[] blank(String src) {
        char[] code = src.toCharArray();
        int pos = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            int end;
            if (src.startsWith("//", pos)) {
                end = src.indexOf('\n', pos);
                end = end < 0 ? src.length() : end;
                blankRange(code, pos, end, ' ');
            } else if (src.startsWith("/*", pos)) {
                end = src.indexOf("*/", pos + 2);
                end = end < 0 ? src.length() : end + 2;
                blankRange(code, pos, end, ' ');
            } else if (src.startsWith("\"\"\"", pos)) {
                end = src.indexOf("\"\"\"", pos + 3);
                while (end > 0 && src.charAt(end - 1) == '\\') {
                    end = src.indexOf("\"\"\"", end + 1);
                }
                end = end < 0 ? src.length() : end + 3;
                blankRange(code, pos + 1, end - 1, 'x');
            } else if (c == '"' || c == '\'') {
                end = pos + 1;
                while (end < src.length() && src.charAt(end) != c && src.charAt(end) != '\n') {
                    end += src.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, src.length());
                blankRange(code, pos + 1, Math.max(pos + 1, end - 1), 'x');
            } else {
                end = pos + 1;
            }
            pos = end;
        }
        return code;
    }

    private static void blankRange(char[] code, int start, int end, char fill) {
        for (int i = start; i < end && i < code.length; i++) {
            if (code[i] != '\n') {
                code[i] = fill;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- scanner

    static List<Hit> scan(Path repoRoot) {
        for (String module : MODULES) {
            assertThat(repoRoot.resolve(module)).as("module %s from %s", module, repoRoot.toAbsolutePath())
                    .isDirectory();
        }
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            Path main = repoRoot.resolve(module + "/src/main/java");
            if (Files.isDirectory(main)) {
                try (Stream<Path> paths = Files.walk(main)) {
                    files.addAll(paths.filter(p -> p.toString().endsWith(".java")).sorted().toList());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        MIN_FILES.forEach((prefix, floor) -> assertThat(files.stream()
                        .filter(f -> repoRoot.relativize(f).toString().replace('\\', '/').startsWith(prefix)))
                .as("files scanned under %s", prefix)
                .hasSizeGreaterThanOrEqualTo(floor));
        List<Hit> hits = new ArrayList<>();
        for (Path file : files) {
            try {
                hits.addAll(violations(
                        repoRoot.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return hits;
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    void flagsEqualsOnAToken() {
        assertThat(violations("X.java", "if (stored.getToken().equals(given)) { ok(); }"))
                .hasSize(1);
    }

    @Test
    void flagsObjectsEqualsOnAState() {
        assertThat(violations("X.java", "return Objects.equals(state, expected);"))
                .hasSize(1);
    }

    @Test
    void flagsAnOperatorOnASecret() {
        assertThat(violations("X.java", "if (password != submitted) { deny(); }")).hasSize(1);
    }

    @Test
    void acceptsMessageDigestIsEqual() {
        assertThat(violations("X.java", "MessageDigest.isEqual(a.getTokenHash(), hash);"))
                .isEmpty();
    }

    @Test
    void ignoresCommentsAndLiterals() {
        assertThat(violations("X.java", "// token.equals(x)\nString s = \"token.equals\";\n/* hash == x */"))
                .isEmpty();
    }

    @Test
    void ignoresUnrelatedNames() {
        assertThat(violations("X.java", "variantKey.equals(CONTROL);")).isEmpty();
        assertThat(violations("X.java", "return a.hashCode() == b.hashCode();")).isEmpty();
        assertThat(violations("X.java", "if (token == null || code != 0) { fail(); }")).isEmpty();
        assertThat(violations("X.java", "statusCode.equals(other);")).isEmpty();
    }

    @Test
    void mainSourcesCompareSecretsInConstantTime() {
        List<String> unexpected = scan(ROOT).stream()
                .filter(hit -> !KEPT.containsKey(hit.path() + ":" + hit.text()))
                .map(hit -> hit.path() + ":" + hit.line() + "  " + hit.text())
                .toList();
        assertThat(unexpected)
                .as("secrets compared with equals or == (use MessageDigest.isEqual)")
                .isEmpty();
    }

    @Test
    void keptOnlyListsRealHits() {
        Set<String> keys = new TreeSet<>();
        scan(ROOT).forEach(hit -> keys.add(hit.path() + ":" + hit.text()));
        assertThat(KEPT.keySet().stream().filter(key -> !keys.contains(key)).sorted())
                .as("KEPT entries the scan no longer reports")
                .isEmpty();
    }
}
