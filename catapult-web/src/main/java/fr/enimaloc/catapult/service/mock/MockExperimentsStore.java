package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentVariantDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mock's experiments: one store for the whole application. Answers like catapult-api: 204 for
 * every action, 404 for an unknown experiment, rule, override, or a forced variant of another
 * experiment (or a USER override on an unknown login), 409 for a status change the lifecycle
 * refuses (activate from DRAFT or PAUSED, pause from ACTIVE, end from ACTIVE or PAUSED).
 * It also holds some made-up participants per experiment, so the participants list shows paging.
 */
class MockExperimentsStore {
    private static final int PARTICIPANTS_PAGE_SIZE = 20;

    private static final class Participant {
        final UUID userId;
        final String login;
        final Instant assignedAt;
        UUID variantId;

        Participant(String login, UUID variantId, Instant assignedAt) {
            this.userId = UUID.nameUUIDFromBytes(("mock-participant:" + login).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.login = login;
            this.variantId = variantId;
            this.assignedAt = assignedAt;
        }
    }

    private static final class Experiment {
        final UUID id = UUID.randomUUID();
        final String key;
        final String name;
        final String description;
        ExperimentStatus status;
        final List<ExperimentVariantDto> variants = new ArrayList<>();
        final List<ExperimentRuleDto> rules = new ArrayList<>();
        final List<ExperimentOverrideDto> overrides = new ArrayList<>();
        final List<Participant> participants = new ArrayList<>();

        Experiment(String key, String name, String description, ExperimentStatus status) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.status = status;
        }

        void variant(String key, String name, int weight, boolean control) {
            variants.add(new ExperimentVariantDto(UUID.randomUUID(), key, name, weight, control));
        }

        ExperimentSummaryDto summary() {
            return new ExperimentSummaryDto(id, key, name, status, List.copyOf(variants), rules.size());
        }

        ExperimentSettingsDto settings() {
            return new ExperimentSettingsDto(id, key, name, description, status,
                    List.copyOf(variants), List.copyOf(rules), List.copyOf(overrides));
        }
    }

    private final List<Experiment> experiments = new ArrayList<>();

    MockExperimentsStore() {
        Experiment minecraft = new Experiment("minecraft.integration", "Minecraft integration",
                "Offers the Minecraft linking to the members", ExperimentStatus.DRAFT);
        minecraft.variant("control", "Control", 0, true);
        minecraft.variant("enabled", "Enabled", 100, false);
        minecraft.rules.add(new ExperimentRuleDto(UUID.randomUUID(), "RANDOM", 100, null, null, null));
        for (int i = 1; i <= 4; i++) {
            minecraft.participants.add(new Participant("early_bird_" + i, minecraft.variants.get(1).id(), daysAgo(i)));
        }
        experiments.add(minecraft);

        Experiment dashboard = new Experiment("new-dashboard", "New dashboard",
                "A redesigned dashboard for half of the members", ExperimentStatus.ACTIVE);
        dashboard.variant("control", "Control", 50, true);
        dashboard.variant("new", "New dashboard", 50, false);
        for (int i = 1; i <= 45; i++) {
            dashboard.participants.add(new Participant(String.format("viewer_%02d", i),
                    dashboard.variants.get(i % 3 == 0 ? 1 : 0).id(), daysAgo(i % 30)));
        }
        experiments.add(dashboard);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    synchronized List<ExperimentSummaryDto> list() {
        return experiments.stream().map(Experiment::summary).toList();
    }

    synchronized Optional<ExperimentSettingsDto> find(UUID id) {
        Experiment experiment = byId(id);
        return experiment == null ? Optional.empty() : Optional.of(experiment.settings());
    }

    /** "activate", "pause", "end" change the status; "reassign" only answers, the mock has no assignments. */
    synchronized int action(UUID id, String action) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        ExperimentStatus status = experiment.status;
        switch (action) {
            case "activate" -> {
                if (status != ExperimentStatus.DRAFT && status != ExperimentStatus.PAUSED) return 409;
                experiment.status = ExperimentStatus.ACTIVE;
            }
            case "pause" -> {
                if (status != ExperimentStatus.ACTIVE) return 409;
                experiment.status = ExperimentStatus.PAUSED;
            }
            case "end" -> {
                if (status == ExperimentStatus.DRAFT || status == ExperimentStatus.ENDED) return 409;
                experiment.status = ExperimentStatus.ENDED;
            }
            case "reassign" -> { }
            default -> { return 404; }
        }
        return 204;
    }

    /** A variant missing from the map keeps its weight; the members then move as {@link #previewWeights} told. */
    synchronized int setWeights(UUID id, Map<UUID, Integer> weights) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        List<Move> moves = plan(experiment, weights);
        experiment.variants.replaceAll(v -> weights.containsKey(v.id())
                ? new ExperimentVariantDto(v.id(), v.key(), v.name(), weights.get(v.id()), v.control())
                : v);
        moves.forEach(move -> move.members.forEach(p -> p.variantId = move.to));
        return 204;
    }

    /**
     * What {@link #setWeights} would move, as catapult-api does: only the variants now over their target give
     * members, the ones now under it take them, and members forced by a USER FORCE_VARIANT override stay.
     * Nothing is written. Empty for an unknown experiment.
     */
    synchronized Optional<ExperimentRebalanceDto> previewWeights(UUID id, Map<UUID, Integer> weights) {
        Experiment experiment = byId(id);
        if (experiment == null) return Optional.empty();
        return Optional.of(new ExperimentRebalanceDto(plan(experiment, weights).stream()
                .map(m -> new ExperimentRebalanceDto.Move(m.from, m.to, m.members.size())).toList()));
    }

    private record Move(UUID from, UUID to, List<Participant> members) {}

    private static List<Move> plan(Experiment experiment, Map<UUID, Integer> weights) {
        List<ExperimentVariantDto> variants = experiment.variants;
        long[] weight = variants.stream().mapToLong(v -> Math.max(0, weights.getOrDefault(v.id(), v.weight()))).toArray();
        long total = Arrays.stream(weight).sum();
        List<Participant> pool = experiment.participants.stream().filter(p -> !isForced(experiment, p)).toList();
        if (total == 0 || pool.isEmpty()) return List.of();

        // largest-remainder targets: they sum to the pool
        long[] target = new long[weight.length];
        Integer[] byRemainder = new Integer[weight.length];
        long assigned = 0;
        for (int i = 0; i < weight.length; i++) {
            target[i] = pool.size() * weight[i] / total;
            assigned += target[i];
            byRemainder[i] = i;
        }
        long[] remainder = new long[weight.length];
        for (int i = 0; i < weight.length; i++) remainder[i] = pool.size() * weight[i] % total;
        Arrays.sort(byRemainder, Comparator.comparingLong((Integer i) -> remainder[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < pool.size() - assigned; k++) target[byRemainder[k]]++;

        Deque<Participant> leavers = new ArrayDeque<>();
        List<Move> moves = new ArrayList<>();
        long[] deficit = new long[weight.length];
        for (int i = 0; i < weight.length; i++) {
            UUID variantId = variants.get(i).id();
            List<Participant> members = pool.stream().filter(p -> p.variantId.equals(variantId)).toList();
            long diff = members.size() - target[i];
            if (diff > 0) members.subList((int) (members.size() - diff), members.size()).forEach(leavers::add);
            else deficit[i] = -diff;
        }
        for (int i = 0; i < weight.length; i++) {
            List<Participant> taken = new ArrayList<>();
            for (long n = deficit[i]; n > 0 && !leavers.isEmpty(); n--) taken.add(leavers.poll());
            // one move per source variant, as the API groups them
            Map<UUID, List<Participant>> bySource = new LinkedHashMap<>();
            taken.forEach(p -> bySource.computeIfAbsent(p.variantId, k -> new ArrayList<>()).add(p));
            UUID to = variants.get(i).id();
            bySource.forEach((from, members) -> moves.add(new Move(from, to, members)));
        }
        return moves;
    }

    synchronized int addRule(UUID id, ExperimentRuleRequest rule) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        experiment.rules.add(new ExperimentRuleDto(UUID.randomUUID(), rule.ruleType(), rule.percentage(),
                rule.attributeKey(), rule.attributeOperator(), rule.attributeValue()));
        return 204;
    }

    synchronized int deleteRule(UUID id, UUID ruleId) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        return experiment.rules.removeIf(r -> r.id().equals(ruleId)) ? 204 : 404;
    }

    /** {@code userKnown}: whether a USER override's login belongs to a member; the store has no members to ask. */
    synchronized int addOverride(UUID id, ExperimentOverrideRequest override, boolean userKnown) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        if ("USER".equals(override.overrideType()) && !userKnown) return 404;
        UUID target = override.targetVariantId();
        if (target != null && experiment.variants.stream().noneMatch(v -> v.id().equals(target))) return 404;
        experiment.overrides.add(new ExperimentOverrideDto(UUID.randomUUID(), override.overrideType(), override.action(),
                override.priority(), override.twitchUsername(), override.attributeKey(), override.attributeOp(),
                override.attributeVal(), target));
        return 204;
    }

    synchronized int deleteOverride(UUID id, UUID overrideId) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        return experiment.overrides.removeIf(o -> o.id().equals(overrideId)) ? 204 : 404;
    }

    /**
     * One page (20) of the experiment's participants by login, narrowed to a variant and to the logins
     * containing {@code q}; each variant's count ignores that narrowing. Empty for an unknown experiment
     * or a variant of another one (catapult-api's 404).
     */
    synchronized Optional<ExperimentAssignmentsDto> assignments(UUID id, UUID variantId, String q, int page) {
        Experiment experiment = byId(id);
        if (experiment == null || (variantId != null && !hasVariant(experiment, variantId))) return Optional.empty();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Participant> matching = experiment.participants.stream()
                .filter(p -> variantId == null || p.variantId.equals(variantId))
                .filter(p -> p.login.toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing((Participant p) -> p.login))
                .toList();
        int number = Math.max(0, page);
        int from = Math.min(matching.size(), number * PARTICIPANTS_PAGE_SIZE);
        int to = Math.min(matching.size(), from + PARTICIPANTS_PAGE_SIZE);
        List<ExperimentAssignmentDto> rows = matching.subList(from, to).stream()
                .map(p -> new ExperimentAssignmentDto(p.userId, p.login, p.variantId, p.assignedAt, isForced(experiment, p)))
                .toList();
        Map<UUID, Long> counts = new HashMap<>();
        experiment.variants.forEach(v -> counts.put(v.id(), 0L));
        experiment.participants.forEach(p -> counts.merge(p.variantId, 1L, Long::sum));
        int totalPages = (matching.size() + PARTICIPANTS_PAGE_SIZE - 1) / PARTICIPANTS_PAGE_SIZE;
        return Optional.of(new ExperimentAssignmentsDto(new AdminPage<>(rows, number, totalPages, matching.size()), counts));
    }

    /**
     * Moves a participant to another variant of the experiment and keeps them there with a USER
     * FORCE_VARIANT override, replacing the ones they already had (as catapult-api does). 404 unknown
     * experiment, variant of another experiment or non-participant, 400 no variant, 409 ended.
     */
    synchronized int move(UUID id, UUID userId, UUID variantId) {
        Experiment experiment = byId(id);
        if (experiment == null) return 404;
        if (variantId == null) return 400;
        if (experiment.status == ExperimentStatus.ENDED) return 409;
        Participant participant = experiment.participants.stream().filter(p -> p.userId.equals(userId)).findFirst().orElse(null);
        if (participant == null || !hasVariant(experiment, variantId)) return 404;
        experiment.overrides.removeIf(o -> "USER".equals(o.type()) && participant.login.equalsIgnoreCase(o.twitchUsername()));
        experiment.overrides.add(new ExperimentOverrideDto(UUID.randomUUID(), "USER", "FORCE_VARIANT", 0,
                participant.login, null, null, null, variantId));
        participant.variantId = variantId;
        return 204;
    }

    private static boolean isForced(Experiment experiment, Participant participant) {
        return experiment.overrides.stream().anyMatch(o -> "USER".equals(o.type()) && "FORCE_VARIANT".equals(o.action())
                && participant.login.equalsIgnoreCase(o.twitchUsername()));
    }

    private static boolean hasVariant(Experiment experiment, UUID variantId) {
        return experiment.variants.stream().anyMatch(v -> v.id().equals(variantId));
    }

    private Experiment byId(UUID id) {
        return experiments.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }
}
