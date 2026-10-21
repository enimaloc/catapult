package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandMigrationTest {

    private static final String SUMMARY = "https://catapult.test/api/user/summary/tok";

    private static CommandMigration of(EnabledChatCommandDto... commands) {
        return CommandMigration.of(List.of(commands), SUMMARY, "Rien \"ici\"");
    }

    private static List<String> lines(CommandMigration migration, String tool) {
        return migration.tools().stream().filter(t -> t.id().equals(tool)).findFirst().orElseThrow()
                .rows().stream().flatMap(r -> r.lines().stream()).map(CommandMigration.Line::value).toList();
    }

    @Test
    void nothingToMove_noCard() {
        assertThat(CommandMigration.of(List.of(), SUMMARY, "-")).isNull();
        assertThat(CommandMigration.of(List.of(new EnabledChatCommandDto("!game", "VIEWERS", "game")), null, "-")).isNull();
    }

    @Test
    void theToolsThatReadJson_readTheSummary() {
        var migration = of(new EnabledChatCommandDto("!store", "VIEWERS", "store"));

        assertThat(migration.tools()).extracting(CommandMigration.Tool::id)
                .containsExactly("twitchat", "streamerbot", "nightbot", "streamelements", "wizebot");
        assertThat(lines(migration, "twitchat")).containsExactly(SUMMARY, "$.storeUrl", "{STOREURL}");
        assertThat(lines(migration, "streamerbot")).containsExactly(SUMMARY, "%catapult.storeUrl%");
        assertThat(lines(migration, "nightbot")).containsExactly("!commands add !store -ul=everyone $(eval const r = "
                + "$(urlfetch json " + SUMMARY + "); r.inGame && r.storeUrl || \"Rien \\\"ici\\\"\")");
    }

    /** StreamElements and Wizebot can't read JSON: they ask for the field alone. */
    @Test
    void theOthers_askForTheFieldAlone() {
        var migration = of(new EnabledChatCommandDto("!game", "MODERATOR", "game"));

        assertThat(lines(migration, "streamelements")).containsExactly(
                "!command add !game $(customapi " + SUMMARY + "?f=name)", "!command options !game -level 500");
        assertThat(lines(migration, "wizebot")).containsExactly("$urlcall(" + SUMMARY + "?f=name,1)");
        assertThat(lines(migration, "nightbot").getFirst()).contains("-ul=moderator");
    }

    @Test
    void aNestedField_isReadStepByStep() {
        var migration = of(new EnabledChatCommandDto("!release", "VIEWERS", "release"));

        assertThat(lines(migration, "nightbot").getFirst())
                .contains("r.inGame && r.releaseDate && r.releaseDate.releaseDate || ");
        assertThat(lines(migration, "twitchat")).contains("$.releaseDate.releaseDate", "{RELEASEDATE_RELEASEDATE}");
        assertThat(lines(migration, "wizebot").getFirst()).contains("?f=releaseDate.releaseDate");
    }

    @Test
    void aCommandTheApiHasNothingFor_isListedApart() {
        var migration = of(new EnabledChatCommandDto("!game", "VIEWERS", "game"),
                new EnabledChatCommandDto("!setgame", "MODERATOR", "setgame"),
                new EnabledChatCommandDto("!mine", "VIEWERS", null));

        assertThat(migration.unmatched()).containsExactly("!setgame", "!mine");
        assertThat(migration.tools()).allSatisfy(t ->
                assertThat(t.rows()).extracting(CommandMigration.Row::command).containsExactly("!game"));
    }
}
