package fr.enimaloc.catapult.repository.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreference;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreferenceId;
import fr.enimaloc.catapult.domain.notification.NotificationDelivery;
import fr.enimaloc.catapult.domain.notification.NotificationRecipient;
import fr.enimaloc.catapult.domain.notification.NotificationRecipientId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.STREAM_START_CHAT;
import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationDeliveryRepositoryTest {

    @Autowired NotificationDeliveryRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void createForNotification_crossesItsRecipientsWithTheirChannels_inOneStatement() {
        UserAccount twitchat = user("twitchat");
        UserAccount chat = user("chat");
        UserAccount none = user("none");
        UserAccount notRecipient = user("other");
        prefer(twitchat, TWITCHAT);
        prefer(chat, STREAM_START_CHAT);
        prefer(notRecipient, TWITCHAT);
        Notification n = notification(Instant.now());
        recipients(n, twitchat, chat, none);

        int created = repository.createForNotification(n.getId(), Instant.now());
        em.clear();

        assertThat(created).isEqualTo(2);
        assertThat(repository.findAll()).extracting(d -> d.getId().getUserId() + ":" + d.getId().getChannel())
                .containsExactlyInAnyOrder(twitchat.getId() + ":TWITCHAT", chat.getId() + ":STREAM_START_CHAT");
        assertThat(repository.findUserIdsByNotificationAndChannel(n.getId(), TWITCHAT)).containsExactly(twitchat.getId());
    }

    @Test
    void createForNotification_withoutPreferences_createsNothing() {
        UserAccount a = user("a");
        Notification n = notification(Instant.now());
        recipients(n, a);

        assertThat(repository.createForNotification(n.getId(), Instant.now())).isZero();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findPending_isOldestFirst_withItsNotification_andDeletesTargetOnlyTheirRows() {
        UserAccount a = user("a");
        UserAccount b = user("b");
        prefer(a, TWITCHAT);
        prefer(a, STREAM_START_CHAT);
        prefer(b, TWITCHAT);
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Notification newer = notification(t0);
        Notification older = notification(t0.minusSeconds(60));
        recipients(older, a, b);
        repository.createForNotification(older.getId(), t0.minusSeconds(60));
        recipients(newer, a, b);
        repository.createForNotification(newer.getId(), t0);
        em.clear();

        List<NotificationDelivery> pending = repository.findPending(a.getId(), TWITCHAT);
        assertThat(pending).extracting(d -> d.getNotification().getId()).containsExactly(older.getId(), newer.getId());

        assertThat(repository.deleteByUserAndNotification(a.getId(), older.getId())).isEqualTo(2);
        assertThat(repository.deleteByUserAndChannels(a.getId(), List.of(STREAM_START_CHAT))).isEqualTo(1);
        assertThat(repository.deleteByUser(a.getId())).isEqualTo(1);
        assertThat(repository.findPending(b.getId(), TWITCHAT)).hasSize(2);
    }

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        return em.persist(user);
    }

    private void prefer(UserAccount user, NotificationChannel channel) {
        em.persist(new NotificationChannelPreference(new NotificationChannelPreferenceId(user.getId(), channel), Instant.now()));
    }

    private Notification notification(Instant createdAt) {
        Notification n = new Notification();
        n.setTitle("T");
        n.setBody("B");
        n.setSeverity(Severity.INFO);
        n.setCreatedAt(createdAt);
        n.setCreatedBy(UUID.randomUUID());
        n.setAudience(Notification.Audience.TARGETED);
        return em.persist(n);
    }

    private void recipients(Notification n, UserAccount... users) {
        for (UserAccount user : users) {
            NotificationRecipient r = new NotificationRecipient();
            r.setId(new NotificationRecipientId(n.getId(), user.getId()));
            r.setNotification(n);
            r.setUser(user);
            em.persist(r);
        }
        em.flush();
    }
}
