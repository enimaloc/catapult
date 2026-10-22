package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserFlagRepositoryTest {

    @Autowired UserFlagRepository flags;
    @Autowired TestEntityManager em;

    private void flag(String twitchUsername, String key, String value) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(twitchUsername);
        em.persist(user);
        UserFlag flag = new UserFlag();
        flag.setUser(user);
        flag.setFlagKey(key);
        flag.setFlagValue(value);
        em.persist(flag);
    }

    @Test
    void listsEachKeyValuePairOnceSortedByKeyThenValue() {
        flag("a", "beta", "on");
        flag("b", "beta", "on");
        flag("c", "beta", "off");
        flag("d", "tester", null);
        em.flush();
        em.clear();

        List<Object[]> pairs = flags.findDistinctKeyValues();

        assertThat(pairs).containsExactly(
                new Object[] {"beta", "off"}, new Object[] {"beta", "on"}, new Object[] {"tester", null});
    }
}
