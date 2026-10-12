package fr.enimaloc.catapult.repository.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadMode;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.UUID;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_ENABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TwitchatEventActionRepositoryTest {

    @Autowired TwitchatEventActionRepository repository;
    @Autowired TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @Autowired TestEntityManager em;

    private UserAccount user(String name) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(name);
        return em.persist(user);
    }

    private TwitchatEventAction action(UserAccount user, TwitchatNotificationEventType type, int position, boolean enabled) {
        TwitchatEventAction action = new TwitchatEventAction();
        action.setId(UUID.randomUUID());
        action.setUser(user);
        action.setEventType(type);
        action.setPosition(position);
        action.setAction("SEND_MESSAGE");
        action.setMode(TwitchatPayloadMode.FORM);
        action.setDataJson("{\"message\":\"" + position + "\"}");
        action.setEnabled(enabled);
        action.setCreatedAt(Instant.now());
        action.setUpdatedAt(Instant.now());
        return repository.save(action);
    }

    @Test
    void enabledActionsOfAnEvent_inPositionOrder() {
        UserAccount user = user("streamer");
        action(user, STREAM_STARTED, 1, true);
        action(user, STREAM_STARTED, 0, true);
        action(user, STREAM_STARTED, 2, false);
        action(user, BOT_ENABLED, 0, true);
        em.flush();

        assertThat(repository.findByUserAndEventTypeAndEnabledTrueOrderByPositionAsc(user, STREAM_STARTED))
                .extracting(TwitchatEventAction::getPosition).containsExactly(0, 1);
    }

    @Test
    void replacingAReorderedList_doesNotHitThePositionConstraint() {
        UserAccount user = user("streamer");
        action(user, STREAM_STARTED, 0, true);
        action(user, STREAM_STARTED, 1, true);
        em.flush();

        repository.deleteAllOfEvent(user, STREAM_STARTED);
        action(user, STREAM_STARTED, 0, true);
        action(user, STREAM_STARTED, 1, true);
        em.flush();

        assertThat(repository.findByUserOrderByPositionAsc(user)).hasSize(2);
    }

    @Test
    void usersAwaitingEventActions_areEnabledAndNotInitialized() {
        UserAccount waiting = user("waiting");
        UserAccount done = user("done");
        UserAccount disabled = user("disabled");
        settings(waiting, true, false);
        settings(done, true, true);
        settings(disabled, false, false);
        em.flush();

        assertThat(widgetSettingsRepository.findUsersAwaitingEventActions())
                .extracting(UserAccount::getTwitchUsername).containsExactly("waiting");
    }

    private void settings(UserAccount user, boolean enabled, boolean initialized) {
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(user);
        settings.setEnabled(enabled);
        settings.setEventActionsInitialized(initialized);
        em.persist(settings);
    }
}
