package fr.enimaloc.catapult.repository.speedrun;

import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
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

/** The writes that must never bring back a key the member removed. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SpeedrunAccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Autowired SpeedrunAccountRepository accounts;
    @Autowired TestEntityManager em;

    private SpeedrunAccount stored(String apiKeyEnc) {
        SpeedrunAccount row = new SpeedrunAccount(UUID.randomUUID());
        row.setDeclaredUsername("n1");
        row.setApiKeyEnc(apiKeyEnc);
        row.setKeyUserId("k1");
        row.setUpdatedAt(NOW);
        accounts.saveAndFlush(row);
        em.clear();
        return accounts.findById(row.getUserId()).orElseThrow();
    }

    /** A copy of {@code row} as a request that read it earlier still holds it. */
    private SpeedrunAccount staleCopy(SpeedrunAccount row) {
        SpeedrunAccount copy = new SpeedrunAccount(row.getUserId());
        copy.setDeclaredUsername(row.getDeclaredUsername());
        copy.setApiKeyEnc(row.getApiKeyEnc());
        copy.setKeyUserId(row.getKeyUserId());
        copy.setUpdatedAt(row.getUpdatedAt());
        copy.setVersion(row.getVersion());
        return copy;
    }

    @Test
    void aStaleSaveAfterTheKeyWasRemovedFails() {
        SpeedrunAccount row = stored("enc");
        SpeedrunAccount stale = staleCopy(row);
        row.setApiKeyEnc(null);
        row.setKeyUserId(null);
        accounts.saveAndFlush(row);
        em.clear();

        stale.setDeclaredUsername("n2");
        assertThatThrownBy(() -> accounts.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        em.clear();
        assertThat(accounts.findById(row.getUserId())).hasValueSatisfying(fresh ->
                assertThat(fresh.getApiKeyEnc()).isNull());
    }

    @Test
    void markKeyInvalidOnlyTouchesTheKeyStillHeldAndBumpsTheVersion() {
        SpeedrunAccount row = stored("enc");
        long version = row.getVersion();
        row.setApiKeyEnc("enc2");
        accounts.saveAndFlush(row);
        em.clear();

        assertThat(accounts.markKeyInvalid(row.getUserId(), "enc", NOW)).isZero();
        assertThat(accounts.keyRechecked(row.getUserId(), "enc", "x", "x", NOW)).isZero();
        assertThat(accounts.markKeyInvalid(row.getUserId(), "enc2", NOW)).isEqualTo(1);
        em.clear();

        SpeedrunAccount fresh = accounts.findById(row.getUserId()).orElseThrow();
        assertThat(fresh.isKeyInvalid()).isTrue();
        assertThat(fresh.getVersion()).isEqualTo(version + 2);
        assertThat(fresh.getKeyUserId()).isEqualTo("k1");
    }

    @Test
    void keyRecheckedBumpsTheVersionSoAnEarlierReadCannotOverwriteIt() {
        SpeedrunAccount row = stored("enc");
        SpeedrunAccount stale = staleCopy(row);

        assertThat(accounts.keyRechecked(row.getUserId(), "enc", "k9", "Renamed", NOW)).isEqualTo(1);
        em.clear();

        stale.setDeclaredUsername("n2");
        assertThatThrownBy(() -> accounts.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }
}
