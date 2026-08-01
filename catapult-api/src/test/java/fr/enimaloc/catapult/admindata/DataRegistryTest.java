package fr.enimaloc.catapult.admindata;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_data_registry_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class DataRegistryTest {

    @Autowired DataRegistry registry;

    @Test
    void discoversKnownRepositories() {
        assertThat(registry.entries()).isNotEmpty();
        assertThat(registry.get("tw-definition")).isPresent();
        assertThat(registry.get("chat-command-definition")).isPresent();
        assertThat(registry.get("user-account")).isPresent();
    }

    @Test
    void resolvesCorrectEntityClass() {
        Optional<DataRegistry.Entry> entry = registry.get("tw-definition");
        assertThat(entry).isPresent();
        assertThat(entry.get().entityClass()).isEqualTo(TwDefinition.class);
    }

    @Test
    void resolvesCorrectEntityType() {
        Optional<DataRegistry.Entry> entry = registry.get("chat-command-definition");
        assertThat(entry).isPresent();
        assertThat(entry.get().entityType().getJavaType()).isEqualTo(ChatCommandDefinition.class);
    }

    @Test
    void nameIsKebabCaseWithoutRepositorySuffix() {
        assertThat(registry.get("UserAccountRepository")).isEmpty();
        assertThat(registry.get("user-account")).isPresent();
        assertThat(registry.get("user-account").get().entityClass()).isEqualTo(UserAccount.class);
    }

    @Test
    void unknownNameReturnsEmpty() {
        assertThat(registry.get("does-not-exist")).isEmpty();
    }
}
