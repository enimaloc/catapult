package fr.enimaloc.catapult.service.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentRebalancerTest {

    private static ExperimentVariant variant(int internalId, int weight) {
        ExperimentVariant v = new ExperimentVariant();
        v.setId(UUID.randomUUID());
        v.setKey("v" + internalId);
        v.setInternalId(internalId);
        v.setWeight(weight);
        return v;
    }

    private static List<ExperimentAssignmentRef> members(ExperimentVariant in, int count) {
        List<ExperimentAssignmentRef> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ExperimentAssignmentRef(UUID.randomUUID(), UUID.randomUUID(), in.getId()));
        }
        return out;
    }

    private static List<ExperimentAssignmentRef> concat(List<ExperimentAssignmentRef>... parts) {
        List<ExperimentAssignmentRef> all = new ArrayList<>();
        for (List<ExperimentAssignmentRef> p : parts) all.addAll(p);
        return all;
    }

    private static Map<UUID, Integer> weights(Object... pairs) {
        Map<UUID, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(((ExperimentVariant) pairs[i]).getId(), (Integer) pairs[i + 1]);
        return m;
    }

    @Test
    void shrinkingTheBigVariant_movesExactlyTheSurplusAndNobodyElse() {
        ExperimentVariant control = variant(1, 90);
        ExperimentVariant enabled = variant(2, 10);
        List<ExperimentAssignmentRef> pool = concat(members(control, 900), members(enabled, 100));

        var plan = ExperimentRebalancer.compute("exp", List.of(control, enabled), weights(control, 80, enabled, 20), pool);

        assertThat(plan.moves()).singleElement().satisfies(m -> {
            assertThat(m.fromVariantId()).isEqualTo(control.getId());
            assertThat(m.toVariantId()).isEqualTo(enabled.getId());
            assertThat(m.assignmentIds()).hasSize(100);
        });
        Set<UUID> controlIds = new HashSet<>();
        pool.stream().filter(r -> r.variantId().equals(control.getId())).forEach(r -> controlIds.add(r.id()));
        assertThat(plan.moves().getFirst().assignmentIds()).allMatch(controlIds::contains);
        assertThat(plan.toDto().moves()).singleElement()
                .isEqualTo(new fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto.Move(
                        control.getId(), enabled.getId(), 100));
    }

    @Test
    void threeVariantsWhereOnlyOneShrinks_theUnchangedOneLosesNobody() {
        ExperimentVariant a = variant(1, 50);
        ExperimentVariant b = variant(2, 25);
        ExperimentVariant c = variant(3, 25);
        List<ExperimentAssignmentRef> pool = concat(members(a, 500), members(b, 250), members(c, 250));

        // Weights 30/25/45 on 1000 members give targets 300/250/450: only a shrinks, only c grows.
        var plan = ExperimentRebalancer.compute("exp", List.of(a, b, c), weights(a, 30, b, 25, c, 45), pool);

        assertThat(plan.moves()).singleElement().satisfies(m -> {
            assertThat(m.fromVariantId()).isEqualTo(a.getId());
            assertThat(m.toVariantId()).isEqualTo(c.getId());
            assertThat(m.assignmentIds()).hasSize(200);
        });
        assertThat(plan.moves()).noneMatch(m -> m.fromVariantId().equals(b.getId()) || m.toVariantId().equals(b.getId()));
    }

    @Test
    void fillsDeficitsInVariantOrder() {
        ExperimentVariant a = variant(1, 100);
        ExperimentVariant b = variant(2, 0);
        ExperimentVariant c = variant(3, 0);
        List<ExperimentAssignmentRef> pool = members(a, 90);

        var plan = ExperimentRebalancer.compute("exp", List.of(c, b, a), weights(a, 10, b, 40, c, 50), pool);

        // targets 9 / 36 / 45 : b (internalId 2) is filled before c (3)
        assertThat(plan.toDto().moves()).extracting(m -> m.toVariantId(), m -> m.count())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(b.getId(), 36L),
                        org.assertj.core.groups.Tuple.tuple(c.getId(), 45L));
    }

    @Test
    void largestRemainderTargetsSumToThePoolSize() {
        ExperimentVariant a = variant(1, 1);
        ExperimentVariant b = variant(2, 1);
        ExperimentVariant c = variant(3, 1);

        assertThat(ExperimentRebalancer.targets(10, List.of(a, b, c), weights(a, 1, b, 1, c, 1)))
                .containsExactly(4L, 3L, 3L);
        assertThat(ExperimentRebalancer.targets(7, List.of(a, b, c), weights(a, 1, b, 2, c, 4)))
                .containsExactly(1L, 2L, 4L);
        assertThat(ExperimentRebalancer.targets(100, List.of(a, b, c), weights(a, 1, b, 1, c, 1)).stream()
                .mapToLong(Long::longValue).sum()).isEqualTo(100);
    }

    @Test
    void membersPinnedByAnOverrideAreNotInThePool() {
        // The caller filters pinned users out of the pool; 90 free members, 10 pinned to control are absent.
        ExperimentVariant control = variant(1, 50);
        ExperimentVariant enabled = variant(2, 50);
        List<ExperimentAssignmentRef> all = concat(members(control, 100), members(enabled, 0));
        Set<UUID> pinned = new HashSet<>();
        all.stream().limit(10).forEach(r -> pinned.add(r.userId()));

        var plan = ExperimentRebalancer.compute("exp", List.of(control, enabled), weights(control, 50, enabled, 50),
                ExperimentRebalancer.withoutPinned(all, pinned));

        assertThat(plan.moves()).singleElement().satisfies(m -> {
            assertThat(m.assignmentIds()).hasSize(45);
            assertThat(all.stream().filter(r -> pinned.contains(r.userId())).map(ExperimentAssignmentRef::id))
                    .noneMatch(m.assignmentIds()::contains);
        });
    }

    @Test
    void sameInput_sameLeavers() {
        ExperimentVariant control = variant(1, 90);
        ExperimentVariant enabled = variant(2, 10);
        List<ExperimentAssignmentRef> pool = concat(members(control, 300), members(enabled, 30));
        List<ExperimentAssignmentRef> shuffled = new ArrayList<>(pool);
        java.util.Collections.reverse(shuffled);

        var first = ExperimentRebalancer.compute("exp", List.of(control, enabled), weights(control, 50, enabled, 50), pool);
        var second = ExperimentRebalancer.compute("exp", List.of(control, enabled), weights(control, 50, enabled, 50), shuffled);

        assertThat(second.moves().getFirst().assignmentIds())
                .containsExactlyInAnyOrderElementsOf(first.moves().getFirst().assignmentIds());
    }

    @Test
    void totalWeightZero_movesNobody() {
        ExperimentVariant a = variant(1, 1);
        ExperimentVariant b = variant(2, 1);
        List<ExperimentAssignmentRef> pool = concat(members(a, 10), members(b, 5));

        var plan = ExperimentRebalancer.compute("exp", List.of(a, b), weights(a, 0, b, 0), pool);

        assertThat(plan.moves()).isEmpty();
        assertThat(plan.toDto().moves()).isEmpty();
    }

    @Test
    void alreadyBalanced_movesNobody() {
        ExperimentVariant a = variant(1, 1);
        ExperimentVariant b = variant(2, 1);

        var plan = ExperimentRebalancer.compute("exp", List.of(a, b), weights(a, 1, b, 1),
                concat(members(a, 5), members(b, 5)));

        assertThat(plan.moves()).isEmpty();
    }

    @Test
    void leavers_areTheHighestHashMembers() {
        ExperimentVariant a = variant(1, 1);
        ExperimentVariant b = variant(2, 1);
        // FNV hashes of "<userId>:variant" for experiment "exp": 1 -> 1644285816, 2 -> 691766269,
        // 3 -> 1446246190, 4 -> 1523157603, 5 -> 2134299044, 6 -> 401859705.
        List<ExperimentAssignmentRef> pool = new ArrayList<>();
        Map<UUID, Integer> userOf = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            UUID user = UUID.fromString("00000000-0000-0000-0000-00000000000" + i);
            ExperimentAssignmentRef ref = new ExperimentAssignmentRef(UUID.randomUUID(), user, a.getId());
            pool.add(ref);
            userOf.put(ref.id(), i);
        }

        var plan = ExperimentRebalancer.compute("exp", List.of(a, b), weights(a, 1, b, 1), pool);

        assertThat(plan.moves()).singleElement().satisfies(m ->
                assertThat(m.assignmentIds()).extracting(userOf::get).containsExactlyInAnyOrder(5, 1, 4));
    }
}
