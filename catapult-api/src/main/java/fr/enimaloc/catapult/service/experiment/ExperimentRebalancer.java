package fr.enimaloc.catapult.service.experiment;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRef;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redistributes an experiment's members after a weight change, moving only those out of variants that are now
 * over their target and into variants under theirs.
 *
 * <p>Members pinned by a USER FORCE_VARIANT override never move and don't count. ATTRIBUTE FORCE_VARIANT
 * overrides are not evaluated here: {@code ExperimentService.applyForcedVariant} corrects those members on
 * their next evaluation anyway.
 */
@Component
@RequiredArgsConstructor
public class ExperimentRebalancer {

    private static final int CHUNK = 1000;

    private final ExperimentAssignmentRepository assignmentRepository;
    private final ExperimentOverrideRepository overrideRepository;

    /** The moves from {@code from} to {@code to}, with the assignments to update. */
    public record Move(UUID fromVariantId, UUID toVariantId, List<UUID> assignmentIds) {}

    public record Plan(List<Move> moves) {
        public ExperimentRebalanceDto toDto() {
            return new ExperimentRebalanceDto(moves.stream()
                    .map(m -> new ExperimentRebalanceDto.Move(m.fromVariantId(), m.toVariantId(), m.assignmentIds().size()))
                    .toList());
        }
    }

    /** Computes the plan for {@code weights} (by variant id; a variant not listed keeps its own weight). Writes nothing. */
    public Plan plan(Experiment experiment, Map<UUID, Integer> weights) {
        Set<UUID> pinned = Set.copyOf(overrideRepository.findForcedUserIds(experiment));
        List<ExperimentAssignmentRef> pool = withoutPinned(assignmentRepository.findRefsByExperiment(experiment), pinned);
        return compute(experiment.getKey(), experiment.getVariants(), weights, pool);
    }

    /** Applies a plan with bulk updates, {@value #CHUNK} assignments at a time. */
    public void apply(Experiment experiment, Plan plan) {
        Map<UUID, ExperimentVariant> byId = new HashMap<>();
        experiment.getVariants().forEach(v -> byId.put(v.getId(), v));
        for (Move move : plan.moves()) {
            ExperimentVariant to = byId.get(move.toVariantId());
            List<UUID> ids = move.assignmentIds();
            for (int from = 0; from < ids.size(); from += CHUNK) {
                assignmentRepository.moveToVariant(to, ids.subList(from, Math.min(ids.size(), from + CHUNK)));
            }
        }
    }

    static List<ExperimentAssignmentRef> withoutPinned(List<ExperimentAssignmentRef> all, Set<UUID> pinnedUserIds) {
        return all.stream().filter(r -> !pinnedUserIds.contains(r.userId())).toList();
    }

    static Plan compute(String experimentKey, List<ExperimentVariant> variants, Map<UUID, Integer> weights,
                        List<ExperimentAssignmentRef> pool) {
        List<ExperimentVariant> ordered = variants.stream()
                .sorted(Comparator.comparingInt(ExperimentVariant::getInternalId)).toList();
        long total = ordered.stream().mapToLong(v -> weightOf(v, weights)).sum();
        if (total == 0 || pool.isEmpty()) return new Plan(List.of());

        Map<UUID, List<ExperimentAssignmentRef>> byVariant = new HashMap<>();
        for (ExperimentAssignmentRef r : pool) byVariant.computeIfAbsent(r.variantId(), k -> new ArrayList<>()).add(r);

        List<Long> targets = targets(pool.size(), ordered, weights);
        // Leavers: per over-target variant, the members the draw hash ranks highest.
        Deque<Leaver> leavers = new ArrayDeque<>();
        Map<UUID, Long> deficits = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            ExperimentVariant v = ordered.get(i);
            List<ExperimentAssignmentRef> members = byVariant.getOrDefault(v.getId(), List.of());
            long diff = members.size() - targets.get(i);
            if (diff > 0) {
                members.stream()
                        .sorted(Comparator.comparingInt((ExperimentAssignmentRef r) -> hashOf(r, experimentKey)).reversed()
                                .thenComparing(ExperimentAssignmentRef::userId))
                        .limit(diff)
                        .forEach(r -> leavers.add(new Leaver(v.getId(), r.id())));
            } else if (diff < 0) {
                deficits.put(v.getId(), -diff);
            }
        }

        Map<List<UUID>, List<UUID>> moves = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> deficit : deficits.entrySet()) {
            for (long n = deficit.getValue(); n > 0 && !leavers.isEmpty(); n--) {
                Leaver leaver = leavers.poll();
                moves.computeIfAbsent(List.of(leaver.from(), deficit.getKey()), k -> new ArrayList<>()).add(leaver.assignmentId());
            }
        }
        return new Plan(moves.entrySet().stream()
                .map(e -> new Move(e.getKey().get(0), e.getKey().get(1), List.copyOf(e.getValue()))).toList());
    }

    /** Largest-remainder targets, in {@code ordered} order; they sum to {@code n}. Ties go to the earlier variant. */
    static List<Long> targets(long n, List<ExperimentVariant> ordered, Map<UUID, Integer> weights) {
        long total = ordered.stream().mapToLong(v -> weightOf(v, weights)).sum();
        long[] target = new long[ordered.size()];
        long[] remainder = new long[ordered.size()];
        long assigned = 0;
        for (int i = 0; i < target.length; i++) {
            long quota = n * weightOf(ordered.get(i), weights);
            target[i] = quota / total;
            remainder[i] = quota % total;
            assigned += target[i];
        }
        List<Integer> byRemainder = new ArrayList<>();
        for (int i = 0; i < target.length; i++) byRemainder.add(i);
        byRemainder.sort(Comparator.comparingLong((Integer i) -> remainder[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < n - assigned; k++) target[byRemainder.get(k)]++;
        List<Long> out = new ArrayList<>();
        for (long t : target) out.add(t);
        return out;
    }

    private static long weightOf(ExperimentVariant v, Map<UUID, Integer> weights) {
        Integer w = weights.get(v.getId());
        return Math.max(0, w != null ? w : v.getWeight());
    }

    private static int hashOf(ExperimentAssignmentRef r, String experimentKey) {
        return ExperimentService.fnvHash(r.userId() + ":variant", experimentKey);
    }

    private record Leaver(UUID from, UUID assignmentId) {}
}
