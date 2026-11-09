package fr.enimaloc.catapult.repository.feature;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.feature.SeenFeature;
import fr.enimaloc.catapult.domain.feature.SeenFeatureId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeenFeatureRepositoryTest {

    @Autowired SeenFeatureRepository repository;
    @Autowired TestEntityManager em;

    // insertIfAbsent's ON CONFLICT is PostgreSQL-only: H2 can't run it, so only the read is covered here.
    @Test
    void findFeatureKeysByUserId_listsOnlyThatUsersKeys() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("streamer1");
        em.persist(user);
        UserAccount other = new UserAccount();
        other.setTwitchUsername("streamer2");
        em.persist(other);
        em.persist(seen(user, "channel.commands"));
        em.persist(seen(other, "admin.meta"));
        em.flush();

        assertThat(repository.findFeatureKeysByUserId(user.getId())).containsExactly("channel.commands");
    }

    private static SeenFeature seen(UserAccount user, String key) {
        SeenFeature seen = new SeenFeature();
        seen.setId(new SeenFeatureId(user.getId(), key));
        seen.setSeenAt(Instant.now());
        return seen;
    }
}
