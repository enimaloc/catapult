package fr.enimaloc.catapult.repository.ankama;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import jakarta.persistence.PersistenceException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnkamaRepositoriesTest {

    @Autowired AnkamaServiceAccountRepository accounts;
    @Autowired AnkamaFriendLinkRepository links;
    @Autowired TestEntityManager em;

    private AnkamaServiceAccount account(String name, long ankamaAccountId) {
        AnkamaServiceAccount a = new AnkamaServiceAccount();
        a.setApiKeyEnc("enc-key");
        a.setRefreshTokenEnc("enc-refresh");
        a.setAnkamaAccountId(ankamaAccountId);
        a.setNickname(name);
        a.setTag("0001");
        a.setDeviceId("device");
        a.setHm1("hm1");
        return em.persist(a);
    }

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private AnkamaFriendLink link(UserAccount user, AnkamaServiceAccount bot, Long ankamaUserId) {
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setRequestedName("Streamer");
        link.setRequestedTag("2928");
        link.setAnkamaUserId(ankamaUserId);
        return em.persist(link);
    }

    @Test
    void savesAndFindsALinkWithEachDerivedQuery() {
        AnkamaServiceAccount bot = account("bot", 42L);
        UserAccount streamer = user("streamer");
        link(streamer, bot, 777L);
        em.flush();
        em.clear();

        List<AnkamaFriendLink.Status> pending = List.of(AnkamaFriendLink.Status.PENDING);
        assertThat(accounts.findByAnkamaAccountId(42L)).isPresent();
        assertThat(links.findByUserId(streamer.getId())).get()
                .extracting(AnkamaFriendLink::getStatus).isEqualTo(AnkamaFriendLink.Status.PENDING);
        assertThat(links.findByUser(streamer)).isPresent();
        assertThat(links.findByServiceAccountId(bot.getId())).hasSize(1);
        assertThat(links.findByServiceAccountIdAndAnkamaUserId(bot.getId(), 777L)).isPresent();
        assertThat(links.findByServiceAccountIdAndAnkamaUserId(bot.getId(), 1L)).isEmpty();
        assertThat(links.findByServiceAccountIdAndStatusIn(bot.getId(), pending)).hasSize(1);
        assertThat(links.countByServiceAccountIdAndStatusIn(bot.getId(), pending)).isEqualTo(1);
        assertThat(links.countByServiceAccountIdAndStatusIn(bot.getId(),
                List.of(AnkamaFriendLink.Status.ACCEPTED))).isZero();
        assertThat(links.findByAnkamaUserIdAndStatus(777L, AnkamaFriendLink.Status.PENDING)).hasSize(1);
        assertThat(links.findByAnkamaUserIdAndStatus(777L, AnkamaFriendLink.Status.ACCEPTED)).isEmpty();
    }

    @Test
    void filtersEnabledAccountsByAuthStateInFillOrder() {
        AnkamaServiceAccount second = account("second", 2L);
        second.setFillOrder(2);
        AnkamaServiceAccount first = account("first", 1L);
        first.setFillOrder(1);
        AnkamaServiceAccount stale = account("stale", 3L);
        stale.setAuthState(AnkamaServiceAccount.AuthState.REAUTH_REQUIRED);
        em.flush();

        assertThat(accounts.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AnkamaServiceAccount.AuthState.READY))
                .extracting(AnkamaServiceAccount::displayTag).containsExactly("first#0001", "second#0001");
    }

    @Test
    void rejectsASecondBotWithTheSameAnkamaAccountId() {
        account("one", 42L);
        em.flush();

        assertThatThrownBy(() -> {
            account("two", 42L);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void accountToStringHidesSecrets() {
        AnkamaServiceAccount a = new AnkamaServiceAccount();
        a.setApiKeyEnc("enc-key-blob");
        a.setRefreshTokenEnc("enc-refresh-blob");
        a.setCertificateEnc("enc-cert-blob");

        assertThat(a.toString()).doesNotContain("enc-key-blob", "enc-refresh-blob", "enc-cert-blob");
    }
}
