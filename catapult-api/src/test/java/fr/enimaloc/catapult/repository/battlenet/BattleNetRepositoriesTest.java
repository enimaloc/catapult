package fr.enimaloc.catapult.repository.battlenet;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BattleNetRepositoriesTest {

    @Autowired BattleNetServiceAccountRepository accounts;
    @Autowired BattleNetFriendLinkRepository links;
    @Autowired TestEntityManager em;

    private BattleNetServiceAccount account(String label, long bnetAccountId) {
        BattleNetServiceAccount a = new BattleNetServiceAccount();
        a.setLabel(label);
        a.setEmail(label + "@example.test");
        a.setPasswordEnc("enc-password");
        a.setBnetAccountId(bnetAccountId);
        a.setBattleTag(label + "#1234");
        return em.persist(a);
    }

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    @Test
    void savesAndFindsALinkByServiceAccountAndBnetAccountId() {
        BattleNetServiceAccount bot = account("bot", 42L);
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setUser(user("streamer"));
        link.setServiceAccount(bot);
        link.setBnetAccountId(100000002L);
        link.setBattleTag("Streamer#2928");
        em.persistAndFlush(link);
        em.clear();

        assertThat(links.findByServiceAccountAndBnetAccountId(bot, 100000002L)).isPresent()
                .get().extracting(BattleNetFriendLink::getStatus).isEqualTo(BattleNetFriendLink.Status.PENDING);
        assertThat(links.findByServiceAccountAndBattleTagIgnoreCase(bot, "streamer#2928")).isPresent();
        assertThat(links.findByServiceAccountAndBnetAccountId(bot, 7L)).isEmpty();
        assertThat(accounts.findByBnetAccountId(42L)).isPresent();
    }

    @Test
    void filtersEnabledAccountsByAuthStateInFillOrder() {
        BattleNetServiceAccount second = account("second", 2L);
        second.setFillOrder(2);
        second.setAuthState(BattleNetServiceAccount.AuthState.READY);
        BattleNetServiceAccount first = account("first", 1L);
        first.setFillOrder(1);
        first.setAuthState(BattleNetServiceAccount.AuthState.READY);
        BattleNetServiceAccount fresh = account("fresh", 3L);
        fresh.setFriendLimitReached(true);
        em.flush();

        assertThat(accounts.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(BattleNetServiceAccount.AuthState.READY))
                .extracting(BattleNetServiceAccount::getLabel).containsExactly("first", "second");
        assertThat(accounts.countByEnabledTrue()).isEqualTo(3);
        assertThat(accounts.countByEnabledTrueAndFriendLimitReachedFalse()).isEqualTo(2);
    }

    @Test
    void accountToStringHidesSecrets() {
        BattleNetServiceAccount a = new BattleNetServiceAccount();
        a.setLabel("bot");
        a.setEmail("secret@example.test");
        a.setPasswordEnc("enc-password-blob");
        a.setStorageStateEnc("enc-storage-blob");

        assertThat(a.toString()).doesNotContain("secret@example.test", "enc-password-blob", "enc-storage-blob");
    }
}
