package fr.enimaloc.catapult.repository.epic;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:epictestdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EpicRepositoriesTest {

    @Autowired EpicServiceAccountRepository accounts;
    @Autowired EpicFriendLinkRepository links;
    @Autowired TestEntityManager em;

    private EpicServiceAccount account(String name, String epicAccountId) {
        EpicServiceAccount a = new EpicServiceAccount();
        a.setRefreshTokenEnc("enc-refresh");
        a.setRefreshExpiresAt(Instant.now().plusSeconds(3600));
        a.setEpicAccountId(epicAccountId);
        a.setDisplayName(name);
        return em.persist(a);
    }

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private EpicFriendLink link(UserAccount user, EpicServiceAccount bot, String epicAccountId) {
        EpicFriendLink link = new EpicFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setEpicAccountId(epicAccountId);
        link.setDisplayName("Streamer");
        return em.persist(link);
    }

    @Test
    void savesAndFindsALinkWithEachDerivedQuery() {
        EpicServiceAccount bot = account("bot", "bot-epic-id");
        UserAccount streamer = user("streamer");
        link(streamer, bot, "streamer-epic-id");
        em.flush();
        em.clear();

        List<EpicFriendLink.Status> pending = List.of(EpicFriendLink.Status.PENDING);
        assertThat(accounts.findByEpicAccountId("bot-epic-id")).isPresent();
        assertThat(links.findByUserId(streamer.getId())).get()
                .extracting(EpicFriendLink::getStatus).isEqualTo(EpicFriendLink.Status.PENDING);
        assertThat(links.findByUser(streamer)).isPresent();
        assertThat(links.findByEpicAccountId("streamer-epic-id")).isPresent();
        assertThat(links.findByEpicAccountId("unknown")).isEmpty();
        assertThat(links.findByServiceAccountId(bot.getId())).hasSize(1);
        assertThat(links.findByServiceAccountIdAndStatusIn(bot.getId(), pending)).hasSize(1);
        assertThat(links.countByServiceAccountIdAndStatusIn(bot.getId(), pending)).isEqualTo(1);
        assertThat(links.countByServiceAccountIdAndStatusIn(bot.getId(),
                List.of(EpicFriendLink.Status.ACCEPTED))).isZero();
        assertThat(links.countByStatus(EpicFriendLink.Status.PENDING)).isEqualTo(1);
    }

    @Test
    void filtersEnabledAccountsByAuthStateInFillOrder() {
        EpicServiceAccount second = account("second", "2");
        second.setFillOrder(2);
        EpicServiceAccount first = account("first", "1");
        first.setFillOrder(1);
        EpicServiceAccount stale = account("stale", "3");
        stale.setAuthState(EpicServiceAccount.AuthState.REAUTH_REQUIRED);
        em.flush();

        assertThat(accounts.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(EpicServiceAccount.AuthState.READY))
                .extracting(EpicServiceAccount::getDisplayName).containsExactly("first", "second");
        assertThat(accounts.countByEnabledTrueAndAuthState(EpicServiceAccount.AuthState.READY)).isEqualTo(2);
    }

    @Test
    void rejectsASecondBotWithTheSameEpicAccountId() {
        account("one", "same-id");
        em.flush();

        assertThatThrownBy(() -> {
            account("two", "same-id");
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsASecondLinkWithTheSameEpicAccountId() {
        EpicServiceAccount bot = account("bot", "bot-epic-id");
        link(user("first"), bot, "same-id");
        em.flush();

        assertThatThrownBy(() -> {
            link(user("second"), bot, "same-id");
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void accountToStringHidesTheRefreshToken() {
        EpicServiceAccount a = new EpicServiceAccount();
        a.setRefreshTokenEnc("enc-refresh-blob");

        assertThat(a.toString()).doesNotContain("enc-refresh-blob");
    }
}
