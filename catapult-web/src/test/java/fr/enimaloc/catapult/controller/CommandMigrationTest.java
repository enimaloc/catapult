package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandMigrationTest {

    private static final String BASE = "https://catapult.test/api/user/command/tok/";

    private static CommandMigration.Tool tool(CommandMigration migration, String id) {
        return migration.tools().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void nothingToMove_noCard() {
        assertThat(CommandMigration.of(List.of(), BASE)).isNull();
        assertThat(CommandMigration.of(List.of(new EnabledChatCommandDto("!game", "VIEWERS", false, false)), null)).isNull();
    }

    @Test
    void everyToolGetsItsOwnLine_withItsLimitAndPermission() {
        var migration = CommandMigration.of(List.of(new EnabledChatCommandDto("!game", "MODERATOR", false, false)), BASE);

        assertThat(migration.tools()).extracting(CommandMigration.Tool::id)
                .containsExactly("twitchat", "wizebot", "nightbot", "streamelements", "streamerbot");
        assertThat(tool(migration, "nightbot").rows()).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo("!commands add !game -ul=moderator $(urlfetch " + BASE + "game?max=399)");
            assertThat(row.extra()).isNull();
        });
        assertThat(tool(migration, "streamelements").rows()).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo("!command add !game $(customapi " + BASE + "game?max=400)");
            assertThat(row.extra()).isEqualTo("!command options !game -level 500");
        });
        assertThat(tool(migration, "wizebot").rows().getFirst().line()).isEqualTo("$urlcall(" + BASE + "game?max=500,1)");
        assertThat(tool(migration, "twitchat").rows().getFirst().line()).isEqualTo(BASE + "game?max=500");
        assertThat(tool(migration, "streamerbot").rows().getFirst().role()).isEqualTo("MODERATOR");
    }

    @Test
    void everyoneOnStreamElements_needsNoPermissionLine() {
        var migration = CommandMigration.of(List.of(new EnabledChatCommandDto("!game", "VIEWERS", false, false)), BASE);

        assertThat(tool(migration, "streamelements").rows().getFirst().extra()).isNull();
        assertThat(tool(migration, "nightbot").rows().getFirst().line()).contains("-ul=everyone");
    }

    @Test
    void argumentsArePassedOn_byTheToolsThatCan() {
        var migration = CommandMigration.of(List.of(new EnabledChatCommandDto("!hug", "VIEWERS", false, true)), BASE);

        assertThat(tool(migration, "nightbot").rows().getFirst().line()).contains("hug?max=399&args=$(querystring))");
        assertThat(tool(migration, "streamelements").rows().getFirst().line()).contains("&args=$(queryescape $(1:)))");
        assertThat(tool(migration, "streamerbot").rows().getFirst().line()).endsWith("&args=%rawInputUrlEncoded%");
        assertThat(tool(migration, "twitchat").rows().getFirst()).satisfies(row -> {
            assertThat(row.line()).isEqualTo(BASE + "hug?max=500");
            assertThat(row.argsDropped()).isTrue();
        });
        assertThat(tool(migration, "nightbot").rows().getFirst().argsDropped()).isFalse();
    }

    @Test
    void aCommandThatActs_isListedApart() {
        var migration = CommandMigration.of(List.of(
                new EnabledChatCommandDto("!game", "VIEWERS", false, false),
                new EnabledChatCommandDto("!setgame", "MODERATOR", true, true)), BASE);

        assertThat(migration.acting()).containsExactly("!setgame");
        assertThat(migration.tools()).allSatisfy(t ->
                assertThat(t.rows()).extracting(CommandMigration.Row::command).containsExactly("!game"));
    }
}
