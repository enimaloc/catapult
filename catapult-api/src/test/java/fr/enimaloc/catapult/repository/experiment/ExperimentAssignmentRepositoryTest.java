package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExperimentAssignmentRepositoryTest {

    @Autowired ExperimentAssignmentRepository repository;
    @Autowired TestEntityManager em;

    private Experiment experiment;
    private ExperimentVariant control;
    private ExperimentVariant treatment;
    private ExperimentVariant empty;

    @BeforeEach
    void setUp() {
        experiment = new Experiment();
        experiment.setKey("exp");
        experiment.setName("Exp");
        em.persist(experiment);
        control = variant(experiment, "old");
        treatment = variant(experiment, "new");
        empty = variant(experiment, "unused");
    }

    private ExperimentVariant variant(Experiment owner, String key) {
        ExperimentVariant v = new ExperimentVariant();
        v.setExperiment(owner);
        v.setKey(key);
        v.setName(key);
        return em.persist(v);
    }

    private ExperimentAssignment assign(Experiment owner, ExperimentVariant variant, String username, String at) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(username);
        em.persist(user);
        ExperimentAssignment a = new ExperimentAssignment();
        a.setExperiment(owner);
        a.setVariant(variant);
        a.setUser(user);
        em.persist(a);
        em.flush();
        // assignedAt is stamped on insert: move it afterwards to control the order.
        em.getEntityManager().createNativeQuery("UPDATE experiment_assignments SET assigned_at = ?1 WHERE id = ?2")
                .setParameter(1, Instant.parse(at)).setParameter(2, a.getId()).executeUpdate();
        em.clear();
        return a;
    }

    /** Picks the repository shape the controller would: no nullable bind, a LIKE-escaped pattern. */
    private Page<ExperimentAssignment> search(UUID variantId, String q, int page, int size) {
        PageRequest paging = PageRequest.of(page, size);
        if (q.isEmpty()) {
            return variantId == null ? repository.searchAll(experiment, paging)
                    : repository.searchByVariant(experiment, variantId, paging);
        }
        String pattern = "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return variantId == null ? repository.searchByUsername(experiment, pattern, paging)
                : repository.searchByVariantAndUsername(experiment, variantId, pattern, paging);
    }

    @Test
    void search_withoutFilterListsEveryParticipantNewestFirst() {
        assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        assign(experiment, treatment, "bob", "2026-10-22T10:00:00Z");
        assign(experiment, control, "carol", "2026-10-21T10:00:00Z");

        Page<ExperimentAssignment> page = search(null, "", 0, 20);

        assertThat(page.getContent()).extracting(a -> a.getUser().getTwitchUsername())
                .containsExactly("bob", "carol", "alice");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void search_filtersOnAVariant() {
        assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        assign(experiment, treatment, "bob", "2026-10-22T10:00:00Z");

        Page<ExperimentAssignment> page = search(treatment.getId(), "", 0, 20);

        assertThat(page.getContent()).extracting(a -> a.getUser().getTwitchUsername()).containsExactly("bob");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void search_matchesTheUsernameIgnoringCase() {
        assign(experiment, control, "Alice_Wonder", "2026-10-20T10:00:00Z");
        assign(experiment, control, "bob", "2026-10-21T10:00:00Z");

        Page<ExperimentAssignment> page = search(null, "lice_w", 0, 20);

        assertThat(page.getContent()).extracting(a -> a.getUser().getTwitchUsername()).containsExactly("Alice_Wonder");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void search_blankKeepsAMemberWithoutAUsername() {
        assign(experiment, control, null, "2026-10-20T10:00:00Z");
        assign(experiment, control, "bob", "2026-10-21T10:00:00Z");

        assertThat(search(null, "", 0, 20).getTotalElements()).isEqualTo(2);
        assertThat(search(control.getId(), "", 0, 20).getTotalElements()).isEqualTo(2);
        assertThat(search(null, "bob", 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void search_treatsLikeWildcardsLiterally() {
        assign(experiment, control, "a_b", "2026-10-20T10:00:00Z");
        assign(experiment, control, "axb", "2026-10-21T10:00:00Z");
        assign(experiment, control, "100%", "2026-10-22T10:00:00Z");

        assertThat(search(null, "a_b", 0, 20).getContent()).extracting(a -> a.getUser().getTwitchUsername())
                .containsExactly("a_b");
        assertThat(search(control.getId(), "%", 0, 20).getContent()).extracting(a -> a.getUser().getTwitchUsername())
                .containsExactly("100%");
    }

    @Test
    void search_ignoresOtherExperimentsAndPages() {
        Experiment other = new Experiment();
        other.setKey("other");
        other.setName("Other");
        em.persist(other);
        ExperimentVariant foreign = variant(other, "x");
        assign(other, foreign, "stranger", "2026-10-22T10:00:00Z");
        assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        assign(experiment, control, "bob", "2026-10-21T10:00:00Z");

        Page<ExperimentAssignment> second = search(null, "", 1, 1);

        assertThat(second.getContent()).extracting(a -> a.getUser().getTwitchUsername()).containsExactly("alice");
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalPages()).isEqualTo(2);
    }

    @Test
    void countsByVariant_groupsTheExperimentsParticipants() {
        assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        assign(experiment, control, "bob", "2026-10-21T10:00:00Z");
        assign(experiment, treatment, "carol", "2026-10-22T10:00:00Z");

        List<Object[]> rows = repository.countByVariant(experiment);

        assertThat(rows).extracting(r -> r[0], r -> r[1])
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(control.getId(), 2L),
                        org.assertj.core.groups.Tuple.tuple(treatment.getId(), 1L));
        assertThat(rows).noneMatch(r -> r[0].equals(empty.getId()));
    }
}
