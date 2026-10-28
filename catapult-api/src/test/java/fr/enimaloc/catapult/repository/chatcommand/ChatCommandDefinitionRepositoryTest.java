package fr.enimaloc.catapult.repository.chatcommand;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
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
class ChatCommandDefinitionRepositoryTest {

    @Autowired ChatCommandDefinitionRepository repository;
    @Autowired TestEntityManager em;

    private UserAccount user(String login, UserAccount.Status status) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(status);
        return em.persist(user);
    }

    private void command(UserAccount user, String name, boolean enabled) {
        ChatCommandDefinition d = new ChatCommandDefinition();
        d.setUser(user);
        d.setName(name);
        d.setTemplate("x");
        d.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        d.setEnabled(enabled);
        em.persist(d);
    }

    @Test
    void findsTheActiveAccountsWithAnEnabledCommand_eachOnce() {
        UserAccount two = user("two", UserAccount.Status.ACTIVE);
        command(two, "!game", true);
        command(two, "!store", true);
        command(user("off", UserAccount.Status.ACTIVE), "!game", false);
        command(user("leaving", UserAccount.Status.PENDING_DELETION), "!game", true);
        user("none", UserAccount.Status.ACTIVE);
        em.flush();
        em.clear();

        assertThat(repository.findActiveUsersWithEnabledCommands())
                .extracting(UserAccount::getTwitchUsername).containsExactly("two");
    }
}
