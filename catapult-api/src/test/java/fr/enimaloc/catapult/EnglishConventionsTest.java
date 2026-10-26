package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Code, comments, logs and test names are written in English, read from the sources of catapult-api, catapult-common
 * and catapult-web as text. The texts the API stores or sends to viewers and streamers stay as they are: they are
 * listed in {@link #KEPT} with the reason. {@link #LEGACY} holds the French still left in the sources; it only shrinks.
 */
class EnglishConventionsTest {

    enum Kind {
        COMMENT,
        LITERAL,
        LOG,
        IDENTIFIER,
        TEST_NAME
    }

    /** One French text: {@code path} is relative to the repository root, with {@code /} separators. */
    record Hit(String path, Kind kind, int line, String text) {

        String key() {
            return path + " " + kind;
        }
    }

    private static final String API = "catapult-api/src/main/java/fr/enimaloc/catapult/";
    private static final String WEB = "catapult-web/src/main/java/fr/enimaloc/catapult/";
    private static final String API_TEST = "catapult-api/src/test/java/fr/enimaloc/catapult/";

    /** The texts the API stores or sends, kept as they are: {@code "<path> <KIND>"} → why. */
    static final Map<String, String> KEPT = Map.ofEntries(
            Map.entry(
                    API + "service/notification/TwitchatDefaultPayloads.java LITERAL",
                    "Twitchat defaults the streamer sees and saves; named by the spec"),
            Map.entry(
                    API + "chat/command/ChatCommandGameMigration.java LITERAL",
                    "default chat command templates viewers read, and the old ones matched in stored rows"),
            Map.entry(
                    API + "chat/command/ChatCommandSetGameMigration.java LITERAL",
                    "default chat command templates viewers read, and the old ones matched in stored rows"),
            Map.entry(
                    API + "chat/command/ChatCommandDescriptionMigration.java LITERAL",
                    "default chat command templates viewers read, and the old ones matched in stored rows"),
            Map.entry(
                    API + "chat/command/ChatCommandLegacyPresetMigration.java LITERAL",
                    "default chat command templates viewers read, and the old ones matched in stored rows"),
            Map.entry(
                    API + "experiment/DarkModeExperiment.java LITERAL",
                    "experiment name, description and variants, seeded once into the DB"),
            Map.entry(
                    API + "experiment/InviteButtonPlacementExperiment.java LITERAL",
                    "experiment name, description and variants, seeded once into the DB"),
            Map.entry(
                    API + "experiment/MinecraftIntegrationExperiment.java LITERAL",
                    "experiment name, description and variants, seeded once into the DB"),
            Map.entry(
                    API + "experiment/NotificationBannerExperiment.java LITERAL",
                    "experiment name, description and variants, seeded once into the DB"),
            Map.entry(
                    API + "experiment/RaidRestoreGameExperiment.java LITERAL",
                    "experiment name, description and variants, seeded once into the DB"),
            Map.entry(
                    API + "service/steam/SteamIADisclosure.java LITERAL",
                    "Steam's own localized headings in 20+ languages, matched against store pages"),
            Map.entry(
                    API + "service/debug/DebugRedactor.java LITERAL",
                    "the [masqué] mask, stored in debug exchanges and shown to the member and in Sentry"),
            Map.entry(
                    API + "event/listener/ActivityLogEventListener.java LITERAL",
                    "free-text lines of the streamer's live activity log (SSE)"),
            Map.entry(
                    API + "service/feedback/FeedbackNotificationService.java LITERAL",
                    "free-text lines of the streamer's live activity log (SSE)"),
            Map.entry(
                    WEB + "service/mock/MockApiService.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "service/mock/MockDtddProposalsStore.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "service/mock/MockCustomBindings.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "service/mock/MockPresets.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "service/mock/MockData.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "service/mock/MockNotificationStore.java LITERAL",
                    "mock data imitating French user content and FR Twitchat payloads"),
            Map.entry(
                    WEB + "controller/mock/MockAdminController.java LITERAL",
                    "flash texts of the dev-only mock admin page, whose template is French and not localized"));

    /** The French still in the sources: {@code "<path> <KIND>"}. Each translating task deletes its own entries. */
    static final Set<String> LEGACY = Set.of(
            API + "api/admin/ApiAdminMembersController.java COMMENT",
            API + "api/admin/ApiAdminMinecraftAccountsController.java LITERAL",
            API + "api/chatcommand/ApiChatCommandDslController.java COMMENT",
            API + "api/userapi/DevBackdoorResolver.java IDENTIFIER",
            API + "chat/ChatCommand.java COMMENT",
            API + "chat/ChatCommandEvent.java COMMENT",
            API + "chat/ChatCommandListener.java COMMENT",
            API + "chat/ChatCommandPresetCatalog.java COMMENT",
            API + "chat/ChatCommandsExperimentSeeder.java COMMENT",
            API + "chat/CommandRegistry.java COMMENT",
            API + "chat/DynamicCommandResolver.java COMMENT",
            API + "chat/GameContext.java COMMENT",
            API + "chat/PlaceholderResolver.java COMMENT",
            API + "chat/command/DebugCommand.java COMMENT",
            API + "chat/command/DebugCommand.java LITERAL",
            API + "chat/command/registry/catapult/CatapultSetParamFunction.java COMMENT",
            API + "config/MockWebConfig.java COMMENT",
            API + "event/ChatCommandDefinitionChangedEvent.java COMMENT",
            API + "event/GameDetectedEvent.java COMMENT",
            API + "event/NoGameDetectedEvent.java COMMENT",
            API + "event/listener/MetricsEventListener.java COMMENT",
            API + "event/listener/MetricsEventListener.java LITERAL",
            API + "experiment/ExperimentSpec.java COMMENT",
            API + "getter/BattleNetGameGetter.java COMMENT",
            API + "getter/DetectedGame.java COMMENT",
            API + "getter/GameGetter.java COMMENT",
            API + "getter/GameGetterChain.java COMMENT",
            API + "getter/GameGetterChain.java LITERAL",
            API + "getter/dtdd/DtddApiClient.java COMMENT",
            API + "getter/dtdd/DtddApiKeyRotator.java COMMENT",
            API + "getter/dtdd/DtddApiKeyRotator.java LITERAL",
            API + "getter/steam/SteamRateLimiter.java COMMENT",
            API + "getter/steam/SteamRateLimiter.java LITERAL",
            API + "monitoring/CatapultGauges.java LITERAL",
            API + "repository/igdb/IgdbGameExternalIdRepository.java COMMENT",
            API + "security/CatapultOAuth2User.java COMMENT",
            API + "security/CatapultOAuth2UserService.java COMMENT",
            API + "security/TokenEncryptionService.java COMMENT",
            API + "service/account/AccountService.java COMMENT",
            API + "service/account/AdminMemberService.java COMMENT",
            API + "service/binding/CustomBindingService.java COMMENT",
            API + "service/binding/GameStateService.java COMMENT",
            API + "service/chatcommand/ChatCommandDefinitionService.java COMMENT",
            API + "service/experiment/ExperimentService.java COMMENT",
            API + "service/feedback/ChangelogService.java LITERAL",
            API + "service/igdb/AdminCclService.java COMMENT",
            API + "service/igdb/IgdbClient.java COMMENT",
            API + "service/igdb/IgdbService.java COMMENT",
            API + "service/metrics/CatapultApiMetrics.java COMMENT",
            API + "service/metrics/ExternalApiObservations.java COMMENT",
            API + "service/notification/TwitchatEventDispatcher.java LITERAL",
            API + "service/provider/IgdbRawQueries.java LITERAL",
            API + "service/twitch/EventSubTwitchChatService.java COMMENT",
            API + "service/twitch/IrcTwitchChatService.java COMMENT",
            API + "service/twitch/SystemTwitchAccountService.java COMMENT",
            "catapult-api/src/main/resources/application-mock-web.properties COMMENT",
            "catapult-api/src/main/resources/application.properties COMMENT",
            API_TEST + "chat/PlaceholderResolverTest.java COMMENT",
            API_TEST + "event/listener/MetricsEventListenerTest.java COMMENT",
            API_TEST + "getter/MinecraftPresenceGetterTest.java COMMENT",
            API_TEST + "service/minecraft/MinecraftFriendServiceTest.java COMMENT",
            API_TEST + "service/minecraft/MinecraftTokenServiceTest.java COMMENT",
            API_TEST + "service/minecraft/MsaAuthClientTest.java COMMENT",
            API_TEST + "service/minecraft/XboxAndMinecraftServiceIT.java COMMENT",
            API_TEST + "service/minecraft/XboxAndMinecraftServiceIT.java LOG",
            API_TEST + "service/xbox/XboxServiceTest.java COMMENT",
            WEB + "service/mock/MockCustomBindings.java COMMENT",
            WEB + "service/mock/MockMinecraftAccountsStore.java LITERAL",
            "catapult-web/src/main/resources/static/js/channel-obs-widget.js COMMENT");

    private static final Path ROOT = Path.of("..");
    private static final String SELF = "catapult-api/src/test/java/fr/enimaloc/catapult/EnglishConventionsTest.java";
    private static final List<String> MODULES = List.of("catapult-api", "catapult-common", "catapult-web");

    // ---------------------------------------------------------------------------------------------- detector

    private static final Set<String> FRENCH_WORDS = Set.of(
            "introuvable", "inconnu", "inconnue", "indisponible", "invalide", "requis", "attente", "compte", "comptes",
            "utilisateur", "utilisateurs", "joueur", "joueurs", "jeu", "jeux");
    private static final Set<String> FRENCH_FUNCTION_WORDS = Set.of(
            "le", "la", "les", "un", "une", "des", "du", "de", "et", "est", "sont", "pour", "avec", "sans", "dans",
            "pas", "que", "qui", "quand", "sur", "mais", "ou", "cette", "ces", "leur", "leurs", "aux", "afin", "sinon",
            "chaque", "tous", "toute", "toutes", "aucun", "aucune", "depuis", "lorsque", "nous", "vous", "elle");
    private static final Set<String> FRENCH_IDENTIFIER_WORDS = Set.of(
            "entier", "entiers", "compteur", "nombre", "taille", "pseudo", "joueur", "jeu", "compte", "chaine",
            "utilisateur", "lien", "cle", "valeur", "donnees", "fichier", "etat", "defaut", "parametre", "liste",
            "resultat", "recherche", "envoi", "enregistrer", "supprimer", "ajouter", "modifier", "creer", "verifier",
            "lier", "sortie", "titre", "texte", "aide", "nouveau", "ancien", "dernier", "premier", "suivant",
            "precedent", "actuel", "ami", "amis", "demande", "reponse", "requete", "erreur", "echec", "debut", "fin");
    /**
     * Accented words English prose borrows. A word is accented French unless it is one of these: this tunes the
     * detector for English, it never lets a French text through.
     */
    private static final Set<String> ACCENTED_ENGLISH_WORDS = Set.of(
            "café", "cafés", "cliché", "clichés", "déjà", "façade", "fiancé", "naïve", "naïvely", "résumé", "résumés",
            "pokémon");

    private static final Pattern ACCENTED_WORD = Pattern.compile("\\p{L}*[À-ÖØ-öø-ÿŒœ]\\p{L}*");
    private static final Pattern URL = Pattern.compile("\\b[a-z][a-z0-9+.-]*://\\S*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ELISION = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(l|d|qu|n|c|j|s)['’]\\p{L}", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Edge punctuation of a word in prose. */
    private static final Pattern EDGES = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    /** A chunk that is code, not prose: dotted, slashed, snake_case, camelCase, a call, a number… */
    private static final Pattern CODE_CHUNK = Pattern.compile("[./\\\\_=<>{}()\\[\\]#@:$*+|&%\\d]|\\p{Ll}\\p{Lu}");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[-'’]+");

    /**
     * Whether a text reads as French: an accented word English does not borrow, a word of {@link #FRENCH_WORDS}, two
     * distinct function words, or one function word and an elision such as {@code l'}. URLs and code-like chunks
     * (dotted, snake_case, camelCase, calls) are not prose and are skipped.
     */
    static boolean looksFrench(String text) {
        String prose = URL.matcher(text).replaceAll(" ");
        Matcher accented = ACCENTED_WORD.matcher(prose);
        while (accented.find()) {
            if (!ACCENTED_ENGLISH_WORDS.contains(accented.group().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Set<String> functionWords = new HashSet<>();
        for (String chunk : prose.split("\\s+")) {
            String bare = EDGES.matcher(chunk).replaceAll("");
            if (bare.isEmpty() || CODE_CHUNK.matcher(bare).find()) {
                continue;
            }
            for (String word : WORD_SEPARATOR.split(bare)) {
                String lower = word.toLowerCase(Locale.ROOT);
                if (FRENCH_WORDS.contains(lower)) {
                    return true;
                }
                if (FRENCH_FUNCTION_WORDS.contains(lower)) {
                    functionWords.add(lower);
                }
            }
        }
        return functionWords.size() >= 2 || (!functionWords.isEmpty() && ELISION.matcher(prose).find());
    }

    private static final Pattern IDENTIFIER_PART = Pattern.compile("\\p{Lu}?\\p{Ll}+|\\p{Lu}+(?!\\p{Ll})");

    /** The French word an identifier is built from, split on camelCase and {@code _}, or null. */
    static String frenchIdentifierWord(String identifier) {
        Matcher part = IDENTIFIER_PART.matcher(identifier);
        while (part.find()) {
            String lower = part.group().toLowerCase(Locale.ROOT);
            if (FRENCH_IDENTIFIER_WORDS.contains(lower)) {
                return lower;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------- scanner

    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(?:log|logger|LOG|LOGGER)\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\("
                    + "|\\bSystem\\s*\\.\\s*(?:out|err)\\s*\\.\\s*print\\w*\\s*\\(");
    private static final Pattern JAVA_TEST_NAME = Pattern.compile("@DisplayName\\s*\\(");
    private static final Pattern JS_TEST_NAME = Pattern.compile("(?<![\\w$.])(?:it|test|describe)\\s*\\(");
    private static final Pattern CODE_IDENTIFIER = Pattern.compile("(?<![\\w$])[A-Za-z_$][\\w$]*");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);

    /** Every French text of the scanned sources, this class excepted. */
    static List<Hit> scan(Path repoRoot) {
        for (String module : MODULES) {
            assertThat(repoRoot.resolve(module)).as("module %s from %s", module, repoRoot.toAbsolutePath())
                    .isDirectory();
        }
        List<Hit> hits = new ArrayList<>();
        for (Path file : sources(repoRoot)) {
            String path = repoRoot.relativize(file).toString().replace('\\', '/');
            if (path.equals(SELF)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (path.endsWith(".java")) {
                scanCode(path, text, false, hits);
            } else if (path.endsWith(".js")) {
                scanCode(path, text, true, hits);
            } else if (path.endsWith(".html")) {
                scanHtml(path, text, hits);
            } else {
                scanProperties(path, text, hits);
            }
        }
        return hits;
    }

    private static List<Path> sources(Path root) {
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            for (String tree : List.of("main", "test")) {
                files.addAll(walk(root.resolve(module + "/src/" + tree + "/java"), ".java"));
            }
        }
        files.addAll(walk(root.resolve("catapult-web/src/main/resources/static/js"), ".js").stream()
                .filter(p -> !p.toString().replace('\\', '/').contains("/vendor/"))
                .toList());
        files.addAll(walk(root.resolve("catapult-web/src/test/js"), ".js"));
        files.addAll(walk(root.resolve("catapult-web/src/main/resources/templates"), ".html"));
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("catapult-"))
                    .sorted()
                    .toList()) {
                for (String tree : List.of("main", "test")) {
                    files.addAll(walk(module.resolve("src/" + tree + "/resources"), ".properties").stream()
                            .filter(p -> {
                                String s = p.toString().replace('\\', '/');
                                return !s.contains("/lang/") && !s.contains("/test-lang/");
                            })
                            .toList());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static List<Path> walk(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(p -> p.toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void scanCode(String path, String source, boolean js, List<Hit> hits) {
        Lexer lexer = new Lexer(source, js);
        lexer.lexCode(false);
        // a JS template's ${} literals are found before the template itself
        lexer.literals.sort(Comparator.comparingInt(Token::start));
        String code = new String(lexer.code);
        boolean main = path.contains("/src/main/");

        for (Token comment : lexer.mergedComments()) {
            add(hits, path, Kind.COMMENT, lexer.line(comment.start()), comment.text());
        }

        Set<Integer> logLiterals = new HashSet<>();
        Matcher log = LOG_CALL.matcher(code);
        while (log.find()) {
            int callLine = lexer.line(log.start());
            int close = closingParenthesis(code, log.end() - 1);
            for (int i = 0; i < lexer.literals.size(); i++) {
                Token literal = lexer.literals.get(i);
                int line = lexer.line(literal.start());
                if (literal.start() > log.start() && literal.start() < close && line <= callLine + 1) {
                    logLiterals.add(i);
                }
            }
        }
        Set<Integer> testNames = new HashSet<>();
        Matcher testName = (js ? JS_TEST_NAME : JAVA_TEST_NAME).matcher(code);
        while (testName.find()) {
            for (int i = 0; i < lexer.literals.size(); i++) {
                if (lexer.literals.get(i).start() >= testName.end()) {
                    testNames.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < lexer.literals.size(); i++) {
            Token literal = lexer.literals.get(i);
            Kind kind = testNames.contains(i)
                    ? Kind.TEST_NAME
                    : logLiterals.contains(i) ? Kind.LOG : main ? Kind.LITERAL : null;
            if (kind != null) {
                add(hits, path, kind, lexer.line(literal.start()), literal.text());
            }
        }

        Set<String> seen = new HashSet<>();
        Matcher identifier = CODE_IDENTIFIER.matcher(code);
        while (identifier.find()) {
            String name = identifier.group();
            if (frenchIdentifierWord(name) != null && seen.add(name)) {
                hits.add(new Hit(path, Kind.IDENTIFIER, lexer.line(identifier.start()), name));
            }
        }
    }

    /** The offset of the {@code )} closing the {@code (} at {@code open}, in code whose literals are blanked. */
    private static int closingParenthesis(String code, int open) {
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

    private static void scanHtml(String path, String source, List<Hit> hits) {
        Matcher comment = HTML_COMMENT.matcher(source);
        while (comment.find()) {
            add(hits, path, Kind.COMMENT, lineOf(source, comment.start()), comment.group(1));
        }
    }

    private static void scanProperties(String path, String source, List<Hit> hits) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("#") || line.startsWith("!")) {
                add(hits, path, Kind.COMMENT, i + 1, line.substring(1));
            }
        }
    }

    private static void add(List<Hit> hits, String path, Kind kind, int line, String text) {
        if (looksFrench(text)) {
            hits.add(new Hit(path, kind, line, text.strip().replaceAll("\\s+", " ")));
        }
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record Token(int start, int end, String text, boolean lineComment) {}

    /**
     * Splits Java or JavaScript into code, comments and string literals. {@link #code} is the source with every
     * comment and literal blanked out, so the patterns run on code only. Java char literals and JS regex literals
     * stay code.
     */
    private static final class Lexer {

        private static final Set<String> REGEX_AFTER_WORD = Set.of(
                "return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "yield",
                "await", "instanceof");

        private final String src;
        private final boolean js;
        private final char[] code;
        private final int[] lineStarts;
        private final List<Token> comments = new ArrayList<>();
        private final List<Token> literals = new ArrayList<>();
        private int pos;
        /** The last code character that is not a space, and the identifier it ends, for JS regex literals. */
        private char lastSignificant;
        private String lastWord = "";

        Lexer(String src, boolean js) {
            this.src = src;
            this.js = js;
            this.code = src.toCharArray();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        int line(int offset) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (lineStarts[mid] <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low + 1;
        }

        /** Lexes code until the end, or until the {@code }} closing a JS template's {@code ${}. */
        void lexCode(boolean untilBrace) {
            int depth = 0;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                char next = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
                if (c == '/' && next == '/') {
                    int end = src.indexOf('\n', pos);
                    end = end < 0 ? src.length() : end;
                    comment(pos, end, src.substring(pos + 2, end), true);
                } else if (c == '/' && next == '*') {
                    int end = src.indexOf("*/", pos + 2);
                    end = end < 0 ? src.length() : end + 2;
                    String body = src.substring(pos + 2, Math.max(pos + 2, end - 2));
                    comment(pos, end, body.replaceAll("(?m)^\\s*\\*+", " "), false);
                } else if (!js && c == '"' && src.startsWith("\"\"\"", pos)) {
                    textBlock();
                } else if (c == '"' || (js && c == '\'')) {
                    quoted(c);
                } else if (!js && c == '\'') {
                    charLiteral();
                } else if (js && c == '`') {
                    template();
                } else if (js && c == '/' && regexAllowed()) {
                    regex();
                } else {
                    if (untilBrace) {
                        if (c == '{') {
                            depth++;
                        } else if (c == '}' && depth-- == 0) {
                            return;
                        }
                    }
                    significant(c);
                    pos++;
                }
            }
        }

        private void significant(char c) {
            if (Character.isWhitespace(c)) {
                return;
            }
            if (Character.isJavaIdentifierPart(c)) {
                lastWord = pos > 0 && Character.isJavaIdentifierPart(src.charAt(pos - 1))
                        ? lastWord + c
                        : String.valueOf(c);
            } else {
                lastWord = "";
            }
            lastSignificant = c;
        }

        private boolean regexAllowed() {
            if (lastSignificant == '\0') {
                return true;
            }
            if (Character.isJavaIdentifierPart(lastSignificant)) {
                return REGEX_AFTER_WORD.contains(lastWord);
            }
            return ")]".indexOf(lastSignificant) < 0;
        }

        private void comment(int start, int end, String text, boolean lineComment) {
            comments.add(new Token(start, end, text, lineComment));
            blank(start, end);
            pos = end;
        }

        private void literal(int start, int end, String text) {
            literals.add(new Token(start, end, text, false));
            blank(start, end);
            lastSignificant = '"';
            lastWord = "";
            pos = end;
        }

        private void blank(int start, int end) {
            for (int i = start; i < end; i++) {
                if (code[i] != '\n') {
                    code[i] = ' ';
                }
            }
        }

        private void quoted(char quote) {
            int start = pos;
            StringBuilder text = new StringBuilder();
            int i = pos + 1;
            while (i < src.length() && src.charAt(i) != quote && src.charAt(i) != '\n') {
                if (src.charAt(i) == '\\' && i + 1 < src.length()) {
                    text.append(unescape(src.charAt(i + 1)));
                    i += 2;
                } else {
                    text.append(src.charAt(i++));
                }
            }
            literal(start, Math.min(i + 1, src.length()), text.toString());
        }

        private void textBlock() {
            int start = pos;
            int end = src.indexOf("\"\"\"", pos + 3);
            while (end > 0 && src.charAt(end - 1) == '\\') {
                end = src.indexOf("\"\"\"", end + 1);
            }
            end = end < 0 ? src.length() : end;
            literal(start, Math.min(end + 3, src.length()), src.substring(pos + 3, end));
        }

        private void charLiteral() {
            int i = pos + 1;
            if (i < src.length() && src.charAt(i) == '\\') {
                i += 2;
            }
            int end = src.indexOf('\'', i);
            blank(pos, end < 0 ? src.length() : end + 1);
            lastSignificant = '\'';
            pos = end < 0 ? src.length() : end + 1;
        }

        private void template() {
            int start = pos;
            StringBuilder text = new StringBuilder();
            blank(pos, pos + 1);
            pos++;
            while (pos < src.length() && src.charAt(pos) != '`') {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) {
                    text.append(unescape(src.charAt(pos + 1)));
                    blank(pos, pos + 2);
                    pos += 2;
                } else if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                    blank(pos, pos + 2);
                    pos += 2;
                    lastSignificant = '{';
                    lexCode(true);
                    text.append(' ');
                    blank(pos, Math.min(pos + 1, src.length()));
                    pos++;
                } else {
                    text.append(c);
                    blank(pos, pos + 1);
                    pos++;
                }
            }
            int end = Math.min(pos + 1, src.length());
            blank(pos, end);
            literals.add(new Token(start, end, text.toString(), false));
            lastSignificant = '`';
            lastWord = "";
            pos = end;
        }

        private void regex() {
            int i = pos + 1;
            boolean inClass = false;
            while (i < src.length() && src.charAt(i) != '\n') {
                char c = src.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '[') {
                    inClass = true;
                } else if (c == ']') {
                    inClass = false;
                } else if (c == '/' && !inClass) {
                    break;
                }
                i++;
            }
            i++;
            while (i < src.length() && Character.isLetter(src.charAt(i))) {
                i++;
            }
            lastSignificant = '/';
            lastWord = "";
            pos = Math.min(i, src.length());
        }

        private static String unescape(char c) {
            return switch (c) {
                case 'n', 't', 'r' -> " ";
                default -> String.valueOf(c);
            };
        }

        /** The comments, with {@code //} runs on consecutive lines merged into one block. */
        List<Token> mergedComments() {
            List<Token> merged = new ArrayList<>();
            for (Token comment : comments) {
                if (!merged.isEmpty()) {
                    Token last = merged.getLast();
                    if (last.lineComment()
                            && comment.lineComment()
                            && line(comment.start()) == line(last.end()) + 1
                            && src.substring(last.end(), comment.start()).isBlank()) {
                        merged.set(
                                merged.size() - 1,
                                new Token(last.start(), comment.end(), last.text() + "\n" + comment.text(), true));
                        continue;
                    }
                }
                merged.add(comment);
            }
            return merged;
        }
    }

    // ---------------------------------------------------------------------------------------------- tests

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Gestion de la configuration admin des CCLs.",
                "Pseudo introuvable: ",
                "(en attente)",
                "Compte Minecraft",
                "Chaîne d'auth Minecraft en échec: {}",
                "Si un compte système est configuré, on envoie via lui.",
                // an English loanword does not hide the French around it
                "Le café est fermé",
                // an elision and one function word
                "l'API renvoie une erreur",
                // a French word inside a code-heavy comment
                "Renvoie {@code null} si le joueur n'existe pas"
            })
    void frenchIsCaught(String text) {
        assertThat(looksFrench(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "on par with the API",
                "de-duplicate the list",
                "Smith et al. describe it",
                "the DES key",
                "Unknown account",
                "IGDB token not available: check the IGDB client-id and secret configuration",
                "a la carte",
                // accented loanwords and proper names in English prose
                "Meet at the café and bring your résumé",
                "Pokémon presence from the Switch",
                // a URL and code tokens are not prose
                "see https://example.com/de/la/page and fr.le.des",
                "the des_la constant and leDes()",
                "don't, it's, users' list",
                "EST is not UTC"
            })
    void englishPasses(String text) {
        assertThat(looksFrench(text)).isFalse();
    }

    @Test
    void frenchIdentifiersAreSplitOnCamelCaseAndUnderscores() {
        assertThat(frenchIdentifierWord("BACKDOOR_MASK_PREMIER_ENTIER")).isEqualTo("premier");
        assertThat(frenchIdentifierWord("pseudo")).isEqualTo("pseudo");
        assertThat(frenchIdentifierWord("finalState")).isNull();
        assertThat(frenchIdentifierWord("cleanupList")).isNull();
    }

    @Test
    void theJavaLexerKeepsCharLiteralsAsCodeAndMergesLineComments() {
        List<Hit> hits = new ArrayList<>();
        scanCode(
                "catapult-api/src/main/java/X.java",
                String.join(
                        "\n",
                        "char quote = '\"'; String s = \"pour la suite\";",
                        "// la",
                        "// de",
                        "@DisplayName(\"le compte\") void t() {}",
                        "int pseudoCount = 0;",
                        "log.info(\"ok {}\", id); String x = \"Jeu\";"),
                false,
                hits);
        assertThat(hits)
                .extracting(Hit::kind, Hit::line)
                .containsExactlyInAnyOrder(
                        tuple(Kind.LITERAL, 1),
                        tuple(Kind.COMMENT, 2),
                        tuple(Kind.TEST_NAME, 4),
                        tuple(Kind.IDENTIFIER, 5),
                        tuple(Kind.LITERAL, 6));
    }

    @Test
    void theJsLexerKeepsRegexesAsCodeAndReadsTemplates() {
        List<Hit> hits = new ArrayList<>();
        scanCode(
                "catapult-web/src/main/resources/static/js/x.js",
                String.join(
                        "\n",
                        "const quote = /'/g; // the quote of a regex",
                        "const label = `Total ${count > 1 ? 'des jeux' : ''} items`;",
                        "log.warn('Compte introuvable', id);",
                        "it('affiche le compte', () => {});"),
                true,
                hits);
        assertThat(hits)
                .extracting(Hit::kind, Hit::line)
                .containsExactlyInAnyOrder(
                        tuple(Kind.LITERAL, 2), tuple(Kind.LOG, 3), tuple(Kind.TEST_NAME, 4));
    }

    @Test
    void sourcesAreEnglish() {
        List<String> unexpected = scan(ROOT).stream()
                .filter(hit -> !KEPT.containsKey(hit.key()) && !LEGACY.contains(hit.key()))
                .map(hit -> hit.path() + ":" + hit.line() + " " + hit.kind() + " " + shorten(hit.text()))
                .toList();
        assertThat(unexpected).as("French in the sources (translate it)").isEmpty();
    }

    @Test
    void keptOnlyListsRealHits() {
        Set<String> keys = keys();
        assertThat(KEPT.keySet().stream().filter(key -> !keys.contains(key)).sorted())
                .as("KEPT entries the scan no longer reports")
                .isEmpty();
    }

    @Test
    void legacyOnlyListsRealViolations() {
        Set<String> keys = keys();
        assertThat(LEGACY.stream().filter(key -> !keys.contains(key)).sorted())
                .as("LEGACY entries already translated (delete them)")
                .isEmpty();
    }

    private static Set<String> keys() {
        return scan(ROOT).stream().map(Hit::key).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String shorten(String text) {
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }
}
