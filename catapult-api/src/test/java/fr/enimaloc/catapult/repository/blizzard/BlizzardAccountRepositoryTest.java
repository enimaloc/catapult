package fr.enimaloc.catapult.repository.blizzard;

import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
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

/** The mapping and the writes of the service; the SQL cascade is checked by BlizzardSchemaMigrationTest. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:blizzarddb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BlizzardAccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Autowired BlizzardAccountRepository accounts;
    @Autowired BlizzardWowCharacterRepository characters;
    @Autowired TestEntityManager em;

    private BlizzardAccount account(UUID userId) {
        BlizzardAccount row = new BlizzardAccount(userId);
        row.setRegion("eu");
        row.setUpdatedAt(NOW);
        return accounts.saveAndFlush(row);
    }

    private void character(UUID userId, String region, long id) {
        BlizzardWowCharacter row = new BlizzardWowCharacter();
        row.setUserId(userId);
        row.setRegion(region);
        row.setCharacterId(id);
        row.setRealmSlug("archimonde");
        row.setRealmName("Archimonde");
        row.setName("alpha");
        row.setDisplayName("Alpha");
        row.setLevel(80);
        row.setClassName("Mage");
        row.setFaction("Alliance");
        characters.saveAndFlush(row);
    }

    @Test
    void aCharacterIsKeyedByMemberRegionAndId() {
        UUID userId = UUID.randomUUID();
        account(userId);
        character(userId, "eu", 1);
        character(userId, "us", 1);
        em.clear();

        assertThat(characters.findByUserId(userId)).hasSize(2)
                .extracting(BlizzardWowCharacter::getRegion).containsExactlyInAnyOrder("eu", "us");
    }

    @Test
    void deleteByUserIdOnlyDeletesThatMembersCharacters() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        account(first);
        account(second);
        character(first, "eu", 1);
        character(second, "eu", 1);

        characters.deleteByUserId(first);
        em.clear();

        assertThat(characters.findByUserId(first)).isEmpty();
        assertThat(characters.findByUserId(second)).hasSize(1);
    }

    @Test
    void aStaleSaveFails() {
        UUID userId = UUID.randomUUID();
        BlizzardAccount row = account(userId);
        em.clear();
        BlizzardAccount stale = new BlizzardAccount(userId);
        stale.setRegion("us");
        stale.setUpdatedAt(NOW);
        stale.setVersion(row.getVersion() + 5);

        assertThatThrownBy(() -> accounts.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }
}
