package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
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
class ChatCommandSettingRepositoryTest {

    @Autowired ChatCommandSettingRepository repository;
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

        ChatCommandSetting setting = new ChatCommandSetting();
        setting.setUser(user);
        setting.setKey("language");
        setting.setValue("fr");
        repository.save(setting);
        em.flush();
        em.clear();

        ChatCommandSetting found = repository.findByUserAndKey(user, "language").orElseThrow();
        assertThat(found.getValue()).isEqualTo("fr");
    }

    @Test
    void findByUserReturnsOnlyThatUsersSettings() {
        UserAccount user1 = persistUser("streamer1");
        UserAccount user2 = persistUser("streamer2");

        ChatCommandSetting s1 = new ChatCommandSetting();
        s1.setUser(user1);
        s1.setKey("language");
        s1.setValue("fr");
        repository.save(s1);

        ChatCommandSetting s2 = new ChatCommandSetting();
        s2.setUser(user2);
        s2.setKey("language");
        s2.setValue("en");
        repository.save(s2);
        em.flush();
        em.clear();

        assertThat(repository.findByUser(user1)).extracting(ChatCommandSetting::getValue).containsExactly("fr");
        assertThat(repository.findByUser(user2)).extracting(ChatCommandSetting::getValue).containsExactly("en");
    }

    @Test
    void uniqueConstraintRejectsDuplicateKeyForSameUser() {
        UserAccount user = persistUser("streamer1");

        ChatCommandSetting s1 = new ChatCommandSetting();
        s1.setUser(user);
        s1.setKey("language");
        s1.setValue("fr");
        repository.saveAndFlush(s1);

        ChatCommandSetting s2 = new ChatCommandSetting();
        s2.setUser(user);
        s2.setKey("language");
        s2.setValue("en");

        assertThatThrownBy(() -> repository.saveAndFlush(s2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteByUserAndKeyRemovesOnlyThatRow() {
        UserAccount user = persistUser("streamer1");

        ChatCommandSetting s1 = new ChatCommandSetting();
        s1.setUser(user);
        s1.setKey("language");
        s1.setValue("fr");
        repository.save(s1);

        ChatCommandSetting s2 = new ChatCommandSetting();
        s2.setUser(user);
        s2.setKey("region");
        s2.setValue("eu");
        repository.save(s2);
        em.flush();
        em.clear();

        repository.deleteByUserAndKey(user, "language");
        em.flush();
        em.clear();

        assertThat(repository.findByUserAndKey(user, "language")).isEmpty();
        assertThat(repository.findByUserAndKey(user, "region")).isPresent();
    }
}
