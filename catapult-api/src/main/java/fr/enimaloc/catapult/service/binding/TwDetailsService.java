package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.tw.TwSeverityRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Why a streamer's binding has its TWs: each one's sources as the detection finds them now (recomputed, never
 * stored), the streamer's or an admin's choice otherwise, and the DTDD topics their severity kept off.
 */
@Service
@RequiredArgsConstructor
public class TwDetailsService {

    private final BindingService bindingService;
    private final GameMappingRepository mappingRepository;
    private final TwDefinitionRepository definitions;

    /** The binding's TWs explained; empty when it isn't the user's. */
    public Optional<TwDetailsDto> details(fr.enimaloc.catapult.domain.account.UserAccount user, UUID bindingId) {
        return bindingService.explainTws(user, bindingId).map(this::details);
    }

    private TwDetailsDto details(BindingService.TwExplanation explained) {
        GameBinding binding = explained.binding();
        TwResolverService.Explanation explanation = explained.explanation();
        Map<String, List<TwResolverService.Signal>> bySource = explanation.signals().stream()
                .filter(TwResolverService.Signal::triggers)
                .collect(Collectors.groupingBy(TwResolverService.Signal::twId));
        boolean adminChose = binding.getStatus() == GameBinding.Status.MAPPED && binding.getSourceId() != null
                && mappingRepository.findBySourceTypeAndSourceId(binding.getSourceType(), binding.getSourceId())
                        .map(m -> m.isTwsOverridden()).orElse(false);
        Map<String, TwDefinition> defs = definitions.findAll().stream()
                .collect(Collectors.toMap(TwDefinition::getId, Function.identity(), (a, b) -> a));

        // The binding's TWs, then the ones the detection suggests that it hasn't
        Set<String> ids = new LinkedHashSet<>(binding.getTws());
        ids.addAll(bySource.keySet());
        List<TwDetailsDto.Tw> tws = new ArrayList<>();
        for (String id : ids) {
            boolean active = binding.getTws().contains(id);
            List<TwResolverService.Signal> sources = bySource.getOrDefault(id, List.of());
            String origin = !sources.isEmpty() ? "DETECTED"
                    : binding.isTwOverride() ? "STREAMER"
                    : adminChose ? "ADMIN"
                    : "NO_SOURCE";
            // An admin's TWs replace the detection's: those it suggests, the binding doesn't have, aren't turned off
            if (!active && adminChose && !binding.isTwOverride()) continue;
            TwDefinition def = defs.get(id);
            tws.add(new TwDetailsDto.Tw(id, def == null ? id : def.getLabel(), active, origin,
                    sources.stream().map(s -> source(s, defs)).toList()));
        }
        tws.sort(Comparator.comparing((TwDetailsDto.Tw t) -> !t.active())
                .thenComparing(t -> defs.containsKey(t.id()) ? defs.get(t.id()).getSortOrder() : Integer.MAX_VALUE)
                .thenComparing(TwDetailsDto.Tw::id));
        return new TwDetailsDto(binding.isTwOverride(), tws,
                explanation.dropped().stream().map(s -> source(s, defs)).toList());
    }

    private static TwDetailsDto.Source source(TwResolverService.Signal signal, Map<String, TwDefinition> defs) {
        TwSeverityRule.Verdict v = signal.severity();
        TwDefinition def = defs.get(signal.twId());
        return new TwDetailsDto.Source(signal.twId(), def == null ? signal.twId() : def.getLabel(),
                signal.source().name(), signal.value(), v == null ? null
                : new TwDetailsDto.Severity(v.reason().name(), v.minSeverity(), v.level(), v.votes(),
                        v.percentAtOrAbove()));
    }
}
