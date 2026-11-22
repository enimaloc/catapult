package fr.enimaloc.catapult.repository.retroachievements;

import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The optimistic lock that keeps a stale write from bringing back a key the member removed. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RetroAchievementsAccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Autowired RetroAchievementsAccountRepository accounts;
    @Autowired TestEntityManager em;

    private RetroAchievementsAccount stored(String apiKeyEnc) {
        RetroAchievementsAccount row = new RetroAchievementsAccount(UUID.randomUUID());
        row.setUsername("Scott");
        row.setUserUlid("00003EMFWR7XB8SDPEHB3K56ZQ");
        row.setApiKeyEnc(apiKeyEnc);
        row.setUpdatedAt(NOW);
        accounts.saveAndFlush(row);
        em.clear();
        return accounts.findById(row.getUserId()).orElseThrow();
    }

    private static RetroAchievementsAccount staleCopy(RetroAchievementsAccount row) {
        RetroAchievementsAccount copy = new RetroAchievementsAccount(row.getUserId());
        copy.setUsername(row.getUsername());
        copy.setUserUlid(row.getUserUlid());
        copy.setApiKeyEnc(row.getApiKeyEnc());
        copy.setUpdatedAt(row.getUpdatedAt());
        copy.setVersion(row.getVersion());
        return copy;
    }

    @Test
    void aRowIsSavedAndReadBack() {
        RetroAchievementsAccount row = stored("enc");

        assertThat(row.getUsername()).isEqualTo("Scott");
        assertThat(row.getApiKeyEnc()).isEqualTo("enc");
        assertThat(row.isKeyInvalid()).isFalse();
        assertThat(row.getVersion()).isNotNull();
        assertThat(row.toString()).doesNotContain("enc");
    }

    @Test
    void aStaleSaveAfterTheKeyWasRemovedFails() {
        RetroAchievementsAccount row = stored("enc");
        RetroAchievementsAccount stale = staleCopy(row);
        row.setApiKeyEnc(null);
        accounts.saveAndFlush(row);
        em.clear();

        stale.setKeyInvalid(true);
        assertThatThrownBy(() -> accounts.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(accounts.findById(row.getUserId()).orElseThrow().getApiKeyEnc()).isNull();
    }
}
