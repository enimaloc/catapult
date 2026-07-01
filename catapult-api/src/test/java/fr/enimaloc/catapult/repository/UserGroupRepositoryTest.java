package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserGroupRepositoryTest {

    @Autowired UserGroupRepository groupRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void savesGroupWithMembersAndFindsByKey() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("streamer1");
        em.persist(user);

        UserGroup group = new UserGroup();
        group.setKey("beta-testers");
        group.setName("Beta Testers");
        group.setMembers(Set.of(user));
        groupRepository.save(group);
        em.flush();
        em.clear();

        UserGroup found = groupRepository.findByKey("beta-testers").orElseThrow();
        assertThat(found.getName()).isEqualTo("Beta Testers");
        assertThat(found.getMembers()).hasSize(1);
        assertThat(groupRepository.existsByKey("beta-testers")).isTrue();
    }
}
