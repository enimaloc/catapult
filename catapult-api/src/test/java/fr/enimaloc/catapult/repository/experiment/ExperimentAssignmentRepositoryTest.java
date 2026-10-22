package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import fr.enimaloc.catapult.domain.account.UserGroup;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentOverride;
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
    @Autowired ExperimentOverrideRepository overrides;

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

    @Test
    void findRefsByExperiment_projectsIdsOfThisExperimentOnly() {
        ExperimentAssignment mine = assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        Experiment other = new Experiment();
        other.setKey("other");
        other.setName("Other");
        em.persist(other);
        assign(other, variant(other, "x"), "stranger", "2026-10-22T10:00:00Z");

        List<ExperimentAssignmentRef> refs = repository.findRefsByExperiment(experiment);

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(mine.getId());
            assertThat(r.variantId()).isEqualTo(control.getId());
            assertThat(r.userId()).isEqualTo(mine.getUser().getId());
        });
    }

    @Test
    void moveToVariant_updatesOnlyTheGivenAssignments() {
        ExperimentAssignment a = assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        ExperimentAssignment b = assign(experiment, control, "bob", "2026-10-21T10:00:00Z");
        ExperimentAssignment c = assign(experiment, control, "carol", "2026-10-22T10:00:00Z");

        int updated = repository.moveToVariant(treatment, List.of(a.getId(), c.getId()));

        assertThat(updated).isEqualTo(2);
        assertThat(repository.findRefsByExperiment(experiment))
                .extracting(ExperimentAssignmentRef::id, ExperimentAssignmentRef::variantId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(a.getId(), treatment.getId()),
                        org.assertj.core.groups.Tuple.tuple(b.getId(), control.getId()),
                        org.assertj.core.groups.Tuple.tuple(c.getId(), treatment.getId()));
    }

    @Test
    void findForcedUserIds_listsOnlyUserForceVariantOverridesOfTheExperiment() {
        ExperimentAssignment pinned = assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        ExperimentAssignment excluded = assign(experiment, control, "bob", "2026-10-21T10:00:00Z");
        override(pinned.getUser(), ExperimentOverride.OverrideAction.FORCE_VARIANT);
        override(excluded.getUser(), ExperimentOverride.OverrideAction.FORCE_EXCLUDE);

        assertThat(overrides.findForcedUserIds(experiment)).containsExactly(pinned.getUser().getId());
    }

    @Test
    void moveToVariant_flushesAPendingWeightChangeFirst() {
        assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        ExperimentAssignment a = repository.findAllByExperiment(experiment).getFirst();
        ExperimentVariant managed = em.find(ExperimentVariant.class, control.getId());
        managed.setWeight(77); // dirty, not flushed

        repository.moveToVariant(treatment, List.of(a.getId()));

        Number stored = (Number) em.getEntityManager()
                .createNativeQuery("SELECT weight FROM experiment_variants WHERE id = ?1")
                .setParameter(1, control.getId()).getSingleResult();
        assertThat(stored.intValue()).isEqualTo(77);
    }

    private void flag(UserAccount user, String key, String value) {
        UserFlag f = new UserFlag();
        f.setUser(em.find(UserAccount.class, user.getId()));
        f.setFlagKey(key);
        f.setFlagValue(value);
        em.persist(f);
        em.flush();
        em.clear();
    }

    private void group(String key, UserAccount... members) {
        UserGroup g = new UserGroup();
        g.setKey(key);
        g.setName(key);
        for (UserAccount m : members) g.getMembers().add(em.find(UserAccount.class, m.getId()));
        em.persist(g);
        em.flush();
        em.clear();
    }

    @Test
    void findUserIdsWithFlag_matchesAnyValueNullIncludedAndOnlyAssignedMembers() {
        UserAccount withValue = assign(experiment, control, "alice", "2026-10-20T10:00:00Z").getUser();
        UserAccount withNull = assign(experiment, control, "bob", "2026-10-21T10:00:00Z").getUser();
        UserAccount otherFlag = assign(experiment, control, "carol", "2026-10-22T10:00:00Z").getUser();
        UserAccount notAssigned = new UserAccount();
        notAssigned.setTwitchUsername("dave");
        em.persist(notAssigned);
        flag(withValue, "beta", "yes");
        flag(withNull, "beta", null);
        flag(otherFlag, "other", "yes");
        flag(notAssigned, "beta", "yes");

        assertThat(repository.findUserIdsWithFlag(experiment, "beta"))
                .containsExactlyInAnyOrder(withValue.getId(), withNull.getId());
    }

    @Test
    void findUserIdsWithFlagValue_matchesTheExactValueOnly() {
        UserAccount yes = assign(experiment, control, "alice", "2026-10-20T10:00:00Z").getUser();
        UserAccount no = assign(experiment, control, "bob", "2026-10-21T10:00:00Z").getUser();
        UserAccount nul = assign(experiment, control, "carol", "2026-10-22T10:00:00Z").getUser();
        flag(yes, "beta", "yes");
        flag(no, "beta", "no");
        flag(nul, "beta", null);

        assertThat(repository.findUserIdsWithFlagValue(experiment, "beta", "yes")).containsExactly(yes.getId());
    }

    @Test
    void findUserIdsInGroup_returnsAssignedMembersOfThatGroupOnly() {
        UserAccount in = assign(experiment, control, "alice", "2026-10-20T10:00:00Z").getUser();
        UserAccount elsewhere = assign(experiment, control, "bob", "2026-10-21T10:00:00Z").getUser();
        UserAccount notAssigned = new UserAccount();
        notAssigned.setTwitchUsername("dave");
        em.persist(notAssigned);
        group("testers", in, notAssigned);
        group("others", elsewhere);

        assertThat(repository.findUserIdsInGroup(experiment, "testers")).containsExactly(in.getId());
    }

    @Test
    void findAttributeForceVariants_skipsUserAndNonForceVariantOverrides() {
        ExperimentAssignment a = assign(experiment, control, "alice", "2026-10-20T10:00:00Z");
        override(a.getUser(), ExperimentOverride.OverrideAction.FORCE_VARIANT);
        attributeOverride(ExperimentOverride.OverrideAction.FORCE_EXCLUDE, "group", "in", "x");
        attributeOverride(ExperimentOverride.OverrideAction.FORCE_VARIANT, "group", "in", "testers");

        assertThat(overrides.findAttributeForceVariants(experiment)).singleElement()
                .satisfies(o -> assertThat(o.getAttributeVal()).isEqualTo("testers"));
    }

    private void attributeOverride(ExperimentOverride.OverrideAction action, String key, String op, String val) {
        ExperimentOverride o = new ExperimentOverride();
        o.setExperiment(em.find(Experiment.class, experiment.getId()));
        o.setOverrideType(ExperimentOverride.OverrideType.ATTRIBUTE);
        o.setAction(action);
        o.setAttributeKey(key);
        o.setAttributeOp(op);
        o.setAttributeVal(val);
        em.persist(o);
        em.flush();
        em.clear();
    }

    private void override(UserAccount user, ExperimentOverride.OverrideAction action) {
        ExperimentOverride o = new ExperimentOverride();
        o.setExperiment(experiment);
        o.setOverrideType(ExperimentOverride.OverrideType.USER);
        o.setAction(action);
        o.setTargetUser(em.find(UserAccount.class, user.getId()));
        o.setTargetVariant(action == ExperimentOverride.OverrideAction.FORCE_VARIANT
                ? em.find(ExperimentVariant.class, treatment.getId()) : null);
        em.persist(o);
        em.flush();
        em.clear();
    }

    /** An assigned member with the given status; {@code system} marks a built-in account. */
    private UserAccount assignedMember(ExperimentVariant variant, String username, UserAccount.Status status, boolean system) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(username);
        user.setStatus(status);
        user.setSystemAccount(system);
        em.persist(user);
        ExperimentAssignment a = new ExperimentAssignment();
        a.setExperiment(em.find(Experiment.class, experiment.getId()));
        a.setVariant(em.find(ExperimentVariant.class, variant.getId()));
        a.setUser(user);
        em.persist(a);
        em.flush();
        return user;
    }

    private void notifiableFixture() {
        assignedMember(control, "ctrl-active", UserAccount.Status.ACTIVE, false);
        assignedMember(treatment, "treat-active", UserAccount.Status.ACTIVE, false);
        assignedMember(treatment, "treat-active-2", UserAccount.Status.ACTIVE, false);
        assignedMember(treatment, "treat-inactive", UserAccount.Status.INACTIVE, false);
        assignedMember(control, "ctrl-deleting", UserAccount.Status.PENDING_DELETION, false);
        assignedMember(control, "ctrl-system", UserAccount.Status.ACTIVE, true);
        Experiment other = new Experiment();
        other.setKey("other");
        other.setName("Other");
        em.persist(other);
        ExperimentVariant otherVariant = variant(other, "x");
        UserAccount stranger = new UserAccount();
        stranger.setTwitchUsername("stranger");
        em.persist(stranger);
        ExperimentAssignment a = new ExperimentAssignment();
        a.setExperiment(other);
        a.setVariant(otherVariant);
        a.setUser(stranger);
        em.persist(a);
        em.flush();
        em.clear();
    }

    @Test
    void notifiableRecipients_areTheActiveNonSystemMembersOfEveryVariant() {
        notifiableFixture();

        assertThat(repository.findNotifiableUsers(experiment))
                .extracting(UserAccount::getTwitchUsername)
                .containsExactlyInAnyOrder("ctrl-active", "treat-active", "treat-active-2");
        assertThat(repository.countNotifiableUsers(experiment)).isEqualTo(3);
    }

    @Test
    void notifiableRecipients_canBeNarrowedToOneVariant() {
        notifiableFixture();

        assertThat(repository.findNotifiableUsersByVariant(experiment, treatment.getId()))
                .extracting(UserAccount::getTwitchUsername)
                .containsExactlyInAnyOrder("treat-active", "treat-active-2");
        assertThat(repository.countNotifiableUsersByVariant(experiment, treatment.getId())).isEqualTo(2);
        assertThat(repository.findNotifiableUsersByVariant(experiment, empty.getId())).isEmpty();
        assertThat(repository.countNotifiableUsersByVariant(experiment, empty.getId())).isZero();
    }
}
