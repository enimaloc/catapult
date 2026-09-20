package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.ast.AssignStatement;
import fr.enimaloc.catapult.chat.command.ast.BinaryExpr;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ConcatStatement;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.Expression;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.ast.ObjectLiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.PropertyGetExpr;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites every {@link ContextGetExpr} for one of {@link PlaceholderResolver#KNOWN_PATHS} (the
 * old flat {@code game#name}/{@code tw#active} placeholders) into the equivalent service call
 * ({@code igdb#getCurrentGame().name}, {@code tw#active()}, ...) added over the course of this
 * feature — same value, same behavior, just expressed as a first-class service call instead of
 * the legacy {@code ctx.placeholder(path)} indirection. Runs after {@link ChatCommandAstBackfill}
 * (Order 1) and {@link ChatCommandTemplateMigration} (Order 2) so every row already has an
 * up-to-date ast/template to rewrite.
 *
 * <p>Deliberately scoped to exactly {@code KNOWN_PATHS}: a dynamic per-game {@code tw#<id>}
 * placeholder (any {@code tw#*} path other than {@code tw#active}) is left untouched — it
 * resolves to that TW's label text when active, and there's no service call yet that reproduces
 * "the label if active, nothing otherwise" (only {@code tw#has(id)}, a boolean check, which isn't
 * a safe drop-in replacement for a value that used to print text). {@code ctx#get} keeps serving
 * those until such a function exists.
 */
@Slf4j
@Component
@Order(3)
public class ChatCommandContextGetMigration implements CommandLineRunner {

    private static final Expression IGDB_CURRENT_GAME = new ServiceCallExpr("igdb", "getCurrentGame", List.of());
    private static final Expression TW_ACTIVE = new ServiceCallExpr("tw", "active", List.of());

    private static final Map<String, Expression> REPLACEMENTS = Map.ofEntries(
        Map.entry("game#name", new PropertyGetExpr(IGDB_CURRENT_GAME, "name")),
        Map.entry("game#summary", new PropertyGetExpr(IGDB_CURRENT_GAME, "summary")),
        Map.entry("game#release_date", new PropertyGetExpr(IGDB_CURRENT_GAME, "steamReleaseDate")),
        Map.entry("game#store#url", new PropertyGetExpr(IGDB_CURRENT_GAME, "storeUrl")),
        Map.entry("game#store#steam", new PropertyGetExpr(IGDB_CURRENT_GAME, "storeSteamUrl")),
        Map.entry("game#store#xbox", new PropertyGetExpr(IGDB_CURRENT_GAME, "storeXboxUrl")),
        Map.entry("game#store#battlenet", new PropertyGetExpr(IGDB_CURRENT_GAME, "storeBattlenetUrl")),
        Map.entry("game#store#official", new PropertyGetExpr(IGDB_CURRENT_GAME, "storeOfficialUrl")),
        Map.entry("game#igdb#url", new PropertyGetExpr(IGDB_CURRENT_GAME, "igdbUrl")),
        Map.entry("game#agerating", new PropertyGetExpr(IGDB_CURRENT_GAME, "ageRating")),
        Map.entry("game#rating", new PropertyGetExpr(IGDB_CURRENT_GAME, "rating")),
        Map.entry("game#critic_rating", new PropertyGetExpr(IGDB_CURRENT_GAME, "criticRating")),
        Map.entry("game#platforms", new PropertyGetExpr(IGDB_CURRENT_GAME, "platforms")),
        Map.entry("tw#active", TW_ACTIVE)
    );

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslGenerator generator = new CommandDslGenerator();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandContextGetMigration(ChatCommandDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> changed = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            if (def.getAst() == null) {
                continue;
            }
            try {
                CommandAst decoded = codec.fromJson(def.getAst());
                CommandAst rewritten = new CommandAst(rewriteStatements(decoded.statements()));
                String rewrittenJson = codec.toJson(rewritten);
                if (rewrittenJson.equals(def.getAst())) {
                    continue;
                }
                def.setAst(rewrittenJson);
                def.setTemplate(generator.generate(rewritten));
                changed.add(def);
            } catch (RuntimeException e) {
                log.warn("Skipping ctx#get migration for chat command id={} name={}: {}",
                    def.getId(), def.getName(), e.getMessage());
            }
        }
        repository.saveAll(changed);
    }

    private List<Statement> rewriteStatements(List<Statement> statements) {
        return statements == null ? null : statements.stream().map(this::rewriteStatement).toList();
    }

    private Statement rewriteStatement(Statement statement) {
        return switch (statement) {
            case PrintStatement s -> new PrintStatement(rewriteExpr(s.expr()));
            case VarDeclStatement s -> new VarDeclStatement(s.name(), s.type(), rewriteExpr(s.init()));
            case AssignStatement s -> new AssignStatement(s.name(), rewriteExpr(s.expr()));
            case ConcatStatement s -> new ConcatStatement(s.name(), rewriteExpr(s.expr()));
            case IfStatement s -> new IfStatement((BinaryExpr) rewriteExpr(s.condition()),
                rewriteStatements(s.thenBranch()), rewriteStatements(s.elseBranch()));
            case ForEachStatement s -> new ForEachStatement(s.bindingName(), s.listSource(), rewriteStatements(s.body()));
            default -> statement;
        };
    }

    private Expression rewriteExpr(Expression expr) {
        return switch (expr) {
            case ContextGetExpr e -> REPLACEMENTS.getOrDefault(e.path(), e);
            case PropertyGetExpr e -> new PropertyGetExpr(rewriteExpr(e.target()), e.property());
            case ServiceCallExpr e ->
                new ServiceCallExpr(e.namespace(), e.function(), e.args().stream().map(this::rewriteExpr).toList());
            case BinaryExpr e -> new BinaryExpr(rewriteExpr(e.left()), e.operator(), rewriteExpr(e.right()));
            case ObjectLiteralExpr e -> {
                Map<String, Expression> rewritten = new LinkedHashMap<>();
                e.properties().forEach((key, value) -> rewritten.put(key, rewriteExpr(value)));
                yield new ObjectLiteralExpr(rewritten);
            }
            default -> expr;
        };
    }
}
