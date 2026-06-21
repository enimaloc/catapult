package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.service.SteamStoreService.SteamTwSignals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnBooleanProperty(value = "tw.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TwResolverService {

    private final TwIgdbDescriptorMappingRepository igdbRepo;
    private final TwSteamContentIdMappingRepository steamIdRepo;
    private final TwSteamKeywordRepository steamKwRepo;
    private final TwDtddTopicMappingRepository dtddRepo;
    private final Optional<DtddSignalService> dtddSignalService;
    private final SteamStoreService steamStoreService;

    public record SuggestInput(
        String igdbId,
        Set<Long> igdbDescriptorIds,
        String steamAppId,
        String sourceName
    ) {}

    /** Computes the set of TW slugs that should be pre-activated for this binding input. */
    public Set<String> suggest(SuggestInput in) {
        Set<String> out = new HashSet<>();

        if (in.igdbDescriptorIds() != null && !in.igdbDescriptorIds().isEmpty()) {
            out.addAll(igdbRepo.findTwIdsByDescriptorIds(in.igdbDescriptorIds()));
        }

        if (in.steamAppId() != null) {
            SteamTwSignals s = steamStoreService.fetchTwSignals(List.of(in.steamAppId()))
                .getOrDefault(in.steamAppId(), SteamTwSignals.empty());
            if (!s.contentDescriptorIds().isEmpty()) {
                out.addAll(steamIdRepo.findTwIdsBySteamContentIdIn(s.contentDescriptorIds()));
            }
            if (!s.notesLowercase().isBlank()) {
                String notes = s.notesLowercase();
                out.addAll(steamKwRepo.findAll().stream()
                    .filter(k -> notes.contains(k.getKeyword()))
                    .map(k -> k.getTwId())
                    .collect(Collectors.toSet()));
            }
        }

        dtddSignalService.ifPresent(svc -> {
            Set<String> topics = svc.getYesMostlyTopics(in.igdbId(), in.sourceName());
            if (!topics.isEmpty()) {
                Set<String> lowered = topics.stream()
                    .map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                out.addAll(dtddRepo.findTwIdsByDtddTopicNameInIgnoreCase(lowered));
            }
        });

        return out;
    }
}
