package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ChatCommandParam;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatCommandParamRepositoryTest {

    @Autowired ChatCommandParamRepository repository;
    @Autowired TestEntityManager em;

    private UserAccount persistUser(String twitchUsername) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(twitchUsername);
        em.persist(user);
        return user;
    }

    @Test
    void savesAndFindsByUserAndKey() {
        UserAccount user = persistUser("streamer1");

        ChatCommandParam param = new ChatCommandParam();
        param.setUser(user);
        param.setKey("language");
        param.setValue("fr");
        repository.save(param);
        em.flush();
        em.clear();

        ChatCommandParam found = repository.findByUserAndKey(user, "language").orElseThrow();
        assertThat(found.getValue()).isEqualTo("fr");
    }

    @Test
    void findByUserReturnsOnlyThatUsersParams() {
        UserAccount user1 = persistUser("streamer1");
        UserAccount user2 = persistUser("streamer2");

        ChatCommandParam p1 = new ChatCommandParam();
        p1.setUser(user1);
        p1.setKey("language");
        p1.setValue("fr");
        repository.save(p1);

        ChatCommandParam p2 = new ChatCommandParam();
        p2.setUser(user2);
        p2.setKey("language");
        p2.setValue("en");
        repository.save(p2);
        em.flush();
        em.clear();

        assertThat(repository.findByUser(user1)).extracting(ChatCommandParam::getValue).containsExactly("fr");
        assertThat(repository.findByUser(user2)).extracting(ChatCommandParam::getValue).containsExactly("en");
    }

    @Test
    void uniqueConstraintRejectsDuplicateKeyForSameUser() {
        UserAccount user = persistUser("streamer1");

        ChatCommandParam p1 = new ChatCommandParam();
        p1.setUser(user);
        p1.setKey("language");
        p1.setValue("fr");
        repository.saveAndFlush(p1);

        ChatCommandParam p2 = new ChatCommandParam();
        p2.setUser(user);
        p2.setKey("language");
        p2.setValue("en");

        assertThatThrownBy(() -> repository.saveAndFlush(p2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteByUserAndKeyRemovesOnlyThatRow() {
        UserAccount user = persistUser("streamer1");

        ChatCommandParam p1 = new ChatCommandParam();
        p1.setUser(user);
        p1.setKey("language");
        p1.setValue("fr");
        repository.save(p1);

        ChatCommandParam p2 = new ChatCommandParam();
        p2.setUser(user);
        p2.setKey("region");
        p2.setValue("eu");
        repository.save(p2);
        em.flush();
        em.clear();

        repository.deleteByUserAndKey(user, "language");
        em.flush();
        em.clear();

        assertThat(repository.findByUserAndKey(user, "language")).isEmpty();
        assertThat(repository.findByUserAndKey(user, "region")).isPresent();
    }
}
