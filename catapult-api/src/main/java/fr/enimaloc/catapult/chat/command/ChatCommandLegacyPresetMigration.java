package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-time migration for the {@code store}/{@code release}/{@code igdb}/{@code triggers}
 * presets' new default templates — same treatment as {@link ChatCommandGameMigration}/
 * {@link ChatCommandDescriptionMigration}: these used to read the legacy {@code game#*}/
 * {@code tw#active} context-path placeholders, and now call the equivalent
 * {@code igdb#getCurrentGame()}/{@code tw#active()} service functions instead — same
 * {@link fr.enimaloc.catapult.chat.command.registry.igdb.IgdbGetCurrentGameFunction}/
 * {@link fr.enimaloc.catapult.chat.command.registry.tw.TwActiveFunction} data
 * {@link ChatCommandContextGetMigration} already rewrites stored ASTs to, just expressed
 * directly in the preset's default template text so a freshly-instantiated row never touches
 * the legacy placeholder syntax at all. Only rows whose stored template still exactly matches
 * one of the old hardcoded defaults are rewritten — a genuine customization is left untouched.
 *
 * <p>Also recognizes the intermediate form {@link ChatCommandContextGetMigration} (Order 3,
 * pre-existing) already produced on production before this migration existed — e.g.
 * {@code Get it here: {game#store#url}} became {@code Get it here: {print igdb#getCurrentGame().storeUrl}}
 * in place, and {@code tw#active} became the bare {@code {tw#active()}} service-call tag. The
 * surrounding literal text (and language marker) survives that rewrite untouched, so every
 * variant below is still distinguishable per language, same as {@link ChatCommandGameMigration}.
 */
@Slf4j
@Component
@Order(7)
public class ChatCommandLegacyPresetMigration implements CommandLineRunner {

    private static final Map<String, Map<String, String>> OLD_TO_NEW_TEMPLATE_BY_PRESET = Map.of(
        "store", Map.of(
            "Get it here: {game#store#url}",
            "{if igdb#getCurrentGame().storeUrl != \"\"}Get it here: {print igdb#getCurrentGame().storeUrl}{/if}",
            "Disponible ici : {game#store#url}",
            "{if igdb#getCurrentGame().storeUrl != \"\"}Disponible ici : {print igdb#getCurrentGame().storeUrl}{/if}",
            "Get it here: {print igdb#getCurrentGame().storeUrl}",
            "{if igdb#getCurrentGame().storeUrl != \"\"}Get it here: {print igdb#getCurrentGame().storeUrl}{/if}",
            "Disponible ici : {print igdb#getCurrentGame().storeUrl}",
            "{if igdb#getCurrentGame().storeUrl != \"\"}Disponible ici : {print igdb#getCurrentGame().storeUrl}{/if}"
        ),
        "release", Map.of(
            "Released on {game#release_date}",
            "{if igdb#getCurrentGame().releaseDate != \"\"}Released on {print igdb#getCurrentGame().releaseDate}{/if}",
            "Sortie le {game#release_date}",
            "{if igdb#getCurrentGame().releaseDate != \"\"}Sortie le {print igdb#getCurrentGame().releaseDate}{/if}",
            "Released on {print igdb#getCurrentGame().releaseDate}",
            "{if igdb#getCurrentGame().releaseDate != \"\"}Released on {print igdb#getCurrentGame().releaseDate}{/if}",
            "Sortie le {print igdb#getCurrentGame().releaseDate}",
            "{if igdb#getCurrentGame().releaseDate != \"\"}Sortie le {print igdb#getCurrentGame().releaseDate}{/if}"
        ),
        "igdb", Map.of(
            "IGDB page: {game#igdb#url}",
            "{if igdb#getCurrentGame().igdbUrl != \"\"}IGDB page: {print igdb#getCurrentGame().igdbUrl}{/if}",
            "Fiche IGDB : {game#igdb#url}",
            "{if igdb#getCurrentGame().igdbUrl != \"\"}Fiche IGDB : {print igdb#getCurrentGame().igdbUrl}{/if}",
            "IGDB page: {print igdb#getCurrentGame().igdbUrl}",
            "{if igdb#getCurrentGame().igdbUrl != \"\"}IGDB page: {print igdb#getCurrentGame().igdbUrl}{/if}",
            "Fiche IGDB : {print igdb#getCurrentGame().igdbUrl}",
            "{if igdb#getCurrentGame().igdbUrl != \"\"}Fiche IGDB : {print igdb#getCurrentGame().igdbUrl}{/if}"
        ),
        "triggers", Map.ofEntries(
            Map.entry("⚠ Known triggers for {game#name|this game}: {tw#active|{game#agerating|no information available}}",
                "⚠ Known triggers for {if igdb#getCurrentGame().name != \"\"}{print igdb#getCurrentGame().name}{else}this game{/if}: "
                    + "{if tw#active() != \"\"}{print tw#active()}{else}{if igdb#getCurrentGame().ageRating != \"\"}"
                    + "{print igdb#getCurrentGame().ageRating}{else}no information available{/if}{/if}"),
            Map.entry("⚠ Triggers connus pour {game#name|ce jeu} : {tw#active|{game#agerating|aucune information disponible}}",
                "⚠ Triggers connus pour {if igdb#getCurrentGame().name != \"\"}{print igdb#getCurrentGame().name}{else}ce jeu{/if} : "
                    + "{if tw#active() != \"\"}{print tw#active()}{else}{if igdb#getCurrentGame().ageRating != \"\"}"
                    + "{print igdb#getCurrentGame().ageRating}{else}aucune information disponible{/if}{/if}"),
            Map.entry("⚠ Known triggers for {print igdb#getCurrentGame().name}: {tw#active()}",
                "⚠ Known triggers for {if igdb#getCurrentGame().name != \"\"}{print igdb#getCurrentGame().name}{else}this game{/if}: "
                    + "{if tw#active() != \"\"}{print tw#active()}{else}{if igdb#getCurrentGame().ageRating != \"\"}"
                    + "{print igdb#getCurrentGame().ageRating}{else}no information available{/if}{/if}"),
            Map.entry("⚠ Triggers connus pour {print igdb#getCurrentGame().name} : {tw#active()}",
                "⚠ Triggers connus pour {if igdb#getCurrentGame().name != \"\"}{print igdb#getCurrentGame().name}{else}ce jeu{/if} : "
                    + "{if tw#active() != \"\"}{print tw#active()}{else}{if igdb#getCurrentGame().ageRating != \"\"}"
                    + "{print igdb#getCurrentGame().ageRating}{else}aucune information disponible{/if}{/if}")
        )
    );

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslParser parser = new CommandDslParser();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandLegacyPresetMigration(ChatCommandDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> changed = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            if (def.getPresetKey() == null) continue;
            Map<String, String> oldToNew = OLD_TO_NEW_TEMPLATE_BY_PRESET.get(def.getPresetKey());
            if (oldToNew == null) {
                continue;
            }
            String newTemplate = oldToNew.get(def.getTemplate());
            if (newTemplate == null) {
                continue;
            }
            def.setTemplate(newTemplate);
            def.setAst(codec.toJson(parser.parse(newTemplate)));
            def.setEjectedJs(null);
            changed.add(def);
        }
        if (!changed.isEmpty()) {
            log.info("Migrated {} store/release/igdb/triggers preset row(s) off legacy placeholders", changed.size());
        }
        repository.saveAll(changed);
    }
}
