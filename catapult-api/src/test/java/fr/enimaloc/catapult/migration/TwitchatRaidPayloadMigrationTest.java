package fr.enimaloc.catapult.migration;

import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.service.notification.TwitchatEventDefaults;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads V122 as text: the seed needs a database to run, the payload it writes does not. */
class TwitchatRaidPayloadMigrationTest {

    private static String sql() throws IOException {
        return new ClassPathResource("db/migration/V122__twitchat_raid_payload.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void seedsExactlyTheJavaDefault() throws IOException {
        Matcher json = Pattern.compile("'(\\{\"message\".*\\})'").matcher(sql());
        assertThat(json.find()).isTrue();
        assertThat(json.group(1)).isEqualTo(JsonMapper.builder().build().writeValueAsString(
                TwitchatEventDefaults.chatMessage(TwitchatNotificationEventType.RAIDED_BY_MEMBER)));
    }

    @Test
    void seedsOnlyInitializedAccountsWithoutARaidPayload() throws IOException {
        assertThat(sql())
                .contains("'RAIDED_BY_MEMBER', 0, 'CUSTOM_CHAT_MESSAGE', 'FORM'")
                .contains("WHERE s.event_actions_initialized = TRUE")
                .contains("a.user_id = s.user_id AND a.event_type = 'RAIDED_BY_MEMBER'");
    }
}
