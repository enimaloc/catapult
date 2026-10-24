package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
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
class CustomBindingRepositoryTest {

    @Autowired CustomBindingRepository repository;
    @Autowired CustomBindingActivationRepository activations;
    @Autowired TestEntityManager em;

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private CustomBinding binding(UserAccount user, String name) {
        CustomBinding b = new CustomBinding();
        b.setUser(user);
        b.setName(name);
        b.setTwitchGameId("509660");
        b.setTwitchGameName("Art");
        b.getCcls().add("ProfanityVulgarity");
        b.getTws().add("chill");
        return repository.save(b);
    }

    @Test
    void findsAStreamersBindingByName_whateverItsCase_andCountsOnlyTheirs() {
        UserAccount one = user("one");
        UserAccount two = user("two");
        binding(one, "Art");
        binding(one, "Dev");
        binding(two, "Art");
        em.flush();
        em.clear();

        assertThat(repository.findByUserAndNameIgnoreCase(one, "aRT")).get()
                .satisfies(b -> {
                    assertThat(b.getName()).isEqualTo("Art");
                    assertThat(b.getCcls()).containsExactly("ProfanityVulgarity");
                    assertThat(b.getTws()).containsExactly("chill");
                });
        assertThat(repository.countByUser(one)).isEqualTo(2);
        assertThat(repository.findByUserOrderByCreatedAtAsc(one)).hasSize(2);
    }

    @Test
    void anActivationIsKeyedByTheStreamer_andReplacedWhenSavedAgain() {
        UserAccount one = user("one");
        CustomBinding art = binding(one, "Art");
        CustomBinding dev = binding(one, "Dev");
        activations.save(new CustomBindingActivation(one.getId(), art, Instant.parse("2026-10-24T10:00:00Z")));
        em.flush();
        activations.save(new CustomBindingActivation(one.getId(), dev, Instant.parse("2026-10-24T11:00:00Z")));
        em.flush();
        em.clear();

        assertThat(activations.count()).isEqualTo(1);
        assertThat(activations.findById(one.getId())).get()
                .extracting(a -> a.getCustomBinding().getName()).isEqualTo("Dev");
    }
}
