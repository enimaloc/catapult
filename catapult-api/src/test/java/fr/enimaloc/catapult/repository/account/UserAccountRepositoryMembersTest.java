package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin members listing's query shapes: DB-side order, paging, filter and LIKE escaping. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserAccountRepositoryMembersTest {

    @Autowired UserAccountRepository repository;
    @Autowired TestEntityManager em;

    private UserAccount member(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        return em.persist(account);
    }

    private static List<String> names(Iterable<UserAccount> accounts) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        accounts.forEach(a -> out.add(a.getTwitchUsername()));
        return out;
    }

    @Test
    void membersPage_ordersByUsername_nullsLast_andPages() {
        member("charlie", "3");
        member(null, "9");
        member("alice", "1");
        member("bob", "2");
        em.flush();

        Page<UserAccount> first = repository.findMembersPage(PageRequest.of(0, 2));
        Page<UserAccount> second = repository.findMembersPage(PageRequest.of(1, 2));

        assertThat(names(first)).containsExactly("alice", "bob");
        assertThat(names(second)).containsExactly("charlie", null);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void search_matchesUsernameSubstring_caseInsensitively_orExactTwitchId() {
        member("StreamerOne", "100");
        member("other", "1000");
        member("mystream", "7");
        em.flush();

        assertThat(names(repository.searchMembersPage("%stream%", "stream", PageRequest.of(0, 10))))
                .containsExactly("StreamerOne", "mystream");
        assertThat(names(repository.searchMembersPage("%100%", "100", PageRequest.of(0, 10))))
                .containsExactly("StreamerOne");
        // the id is an exact match, not a substring: "10" finds neither "100" nor "1000" by id
        assertThat(repository.searchMembersPage("%zzz%", "10", PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(names(repository.searchMembersPage("%zzz%", "7", PageRequest.of(0, 10))))
                .containsExactly("mystream");
    }

    @Test
    void search_treatsLikeWildcardsAsLiterals() {
        member("100%_real", "1");
        member("100xyreal", "2");
        member("back\\slash", "3");
        member("backXslash", "4");
        em.flush();

        assertThat(names(repository.searchMembersPage(UserAccountRepository.likePattern("0%_r"), "0%_r",
                PageRequest.of(0, 10)))).containsExactly("100%_real");
        assertThat(names(repository.searchMembersPage(UserAccountRepository.likePattern("k\\s"), "k\\s",
                PageRequest.of(0, 10)))).containsExactly("back\\slash");
        assertThat(repository.searchMembersPage(UserAccountRepository.likePattern("%"), "%", PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void searchTop_isLimitedByThePageable() {
        for (int i = 0; i < 5; i++) {
            member("viewer" + i, "id" + i);
        }
        em.flush();

        assertThat(repository.searchMembers("%viewer%", "viewer", PageRequest.of(0, 3))).hasSize(3);
    }
}
