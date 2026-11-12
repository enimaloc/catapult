package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwSteamKeyword;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwIgdbDescriptorMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamContentIdMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamKeywordRepository;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreService.SteamTwSignals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    /**
     * The TWs suggested, and until when the Steam store was rate limited when a Steam game's signals were asked for
     * (empty: they came, or weren't needed): without them, the TWs miss what Steam would have added.
     */
    public record Suggestion(Set<String> tws, Optional<Instant> steamLimitedUntil) {}

    /** Until when the Steam store is rate limited (a Steam game's TWs resolved meanwhile miss its signals). */
    public Optional<Instant> steamRateLimitedUntil() {
        return steamStoreService.rateLimitedUntil();
    }

    /** Computes the set of TW slugs that should be pre-activated for this binding input. */
    public Set<String> suggest(SuggestInput in) {
        return suggestReporting(in).tws();
    }

    /** As {@link #suggest}, telling when the Steam signals were missed because the store was rate limited. */
    public Suggestion suggestReporting(SuggestInput in) {
        Set<String> out = new HashSet<>();
        Optional<Instant> steamLimitedUntil = Optional.empty();

        if (in.igdbDescriptorIds() != null && !in.igdbDescriptorIds().isEmpty()) {
            out.addAll(igdbRepo.findTwIdsByDescriptorIds(in.igdbDescriptorIds()));
        }

        if (in.steamAppId() != null) {
            Map<String, SteamTwSignals> signals = steamStoreService.fetchTwSignals(List.of(in.steamAppId()));
            if (!signals.containsKey(in.steamAppId())) steamLimitedUntil = steamStoreService.rateLimitedUntil();
            SteamTwSignals s = signals.getOrDefault(in.steamAppId(), SteamTwSignals.empty());
            if (!s.contentDescriptorIds().isEmpty()) {
                out.addAll(steamIdRepo.findTwIdsBySteamContentIdIn(s.contentDescriptorIds()));
            }
            if (!s.notesLowercase().isBlank()) {
                String notes = s.notesLowercase();
                out.addAll(steamKwRepo.findAll().stream()
                    .filter(k -> notes.contains(k.getKeyword()))
                    .map(TwSteamKeyword::getTwId)
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

        return new Suggestion(out, steamLimitedUntil);
    }
}
