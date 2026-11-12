package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwDtddTopicMapping;
import fr.enimaloc.catapult.domain.tw.TwIgdbDescriptorMapping;
import fr.enimaloc.catapult.domain.tw.TwSteamContentIdMapping;
import fr.enimaloc.catapult.domain.tw.TwSteamKeyword;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwIgdbDescriptorMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamContentIdMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamKeywordRepository;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService.TopicSignals;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreService.SteamTwSignals;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** The level votes a DTDD topic needs for its severity to keep a TW off (see {@link TwSeverityRule}). */
    @Setter @Value("${dtdd.severity.min-votes:3}") private int dtddSeverityMinVotes = 3;

    public record SuggestInput(
        String igdbId,
        Set<Long> igdbDescriptorIds,
        String steamAppId,
        String sourceName
    ) {}

    /** Where a signal comes from. */
    public enum SourceType { IGDB_DESCRIPTOR, STEAM_CONTENT, STEAM_KEYWORD, DTDD_TOPIC }

    /**
     * One signal for a TW: its source and what in it ({@code value}: the IGDB descriptor's or the Steam content
     * descriptor's ID, the Steam keyword, the DTDD topic); {@code severity} a DTDD topic's verdict, null for the rest.
     */
    public record Signal(String twId, SourceType source, String value, TwSeverityRule.Verdict severity) {
        public boolean triggers() {
            return severity == null || severity.triggers();
        }
    }

    /**
     * Every signal found for a game: those that turn their TW on, and the DTDD topics kept off by their severity
     * ({@link #dropped}). {@link #twIds} is what {@link #suggest} gives. {@code steamLimitedUntil}: until when the
     * Steam store was rate limited when a Steam game's signals were asked for (empty: they came, or weren't needed);
     * without them, the TWs miss what Steam would have added.
     */
    public record Explanation(List<Signal> signals, Optional<Instant> steamLimitedUntil) {

        /** Signals Steam didn't fail to give. */
        public Explanation(List<Signal> signals) {
            this(signals, Optional.empty());
        }

        public Set<String> twIds() {
            return signals.stream().filter(Signal::triggers).map(Signal::twId).collect(Collectors.toSet());
        }

        public List<Signal> dropped() {
            return signals.stream().filter(s -> !s.triggers()).toList();
        }
    }

    /** The TWs suggested, and until when the Steam store was rate limited when its signals were missed. */
    public record Suggestion(Set<String> tws, Optional<Instant> steamLimitedUntil) {}

    /** Until when the Steam store is rate limited (a Steam game's TWs resolved meanwhile miss its signals). */
    public Optional<Instant> steamRateLimitedUntil() {
        return steamStoreService.rateLimitedUntil();
    }

    /** As {@link #suggest}, telling when the Steam signals were missed because the store was rate limited. */
    public Suggestion suggestReporting(SuggestInput in) {
        Explanation explanation = explain(in);
        return new Suggestion(explanation.twIds(), explanation.steamLimitedUntil());
    }

    /** Computes the set of TW slugs that should be pre-activated for this binding input. */
    public Set<String> suggest(SuggestInput in) {
        return explain(in).twIds();
    }

    /** As {@link #suggest}, telling which source turned each TW on, and which DTDD topics their severity kept off. */
    public Explanation explain(SuggestInput in) {
        List<Signal> out = new ArrayList<>();
        Optional<Instant> steamLimitedUntil = Optional.empty();

        if (in.igdbDescriptorIds() != null && !in.igdbDescriptorIds().isEmpty()) {
            for (TwIgdbDescriptorMapping m : igdbRepo.findAllByDescriptorIdIn(in.igdbDescriptorIds())) {
                out.add(new Signal(m.getTwId(), SourceType.IGDB_DESCRIPTOR, String.valueOf(m.getDescriptorId()), null));
            }
        }

        if (in.steamAppId() != null) {
            Map<String, SteamTwSignals> fetched = steamStoreService.fetchTwSignals(List.of(in.steamAppId()));
            if (!fetched.containsKey(in.steamAppId())) steamLimitedUntil = steamStoreService.rateLimitedUntil();
            SteamTwSignals s = fetched.getOrDefault(in.steamAppId(), SteamTwSignals.empty());
            if (!s.contentDescriptorIds().isEmpty()) {
                for (TwSteamContentIdMapping m : steamIdRepo.findAllBySteamContentIdIn(s.contentDescriptorIds())) {
                    out.add(new Signal(m.getTwId(), SourceType.STEAM_CONTENT, String.valueOf(m.getSteamContentId()), null));
                }
            }
            if (!s.notesLowercase().isBlank()) {
                String notes = s.notesLowercase();
                for (TwSteamKeyword k : steamKwRepo.findAll()) {
                    if (notes.contains(k.getKeyword())) {
                        out.add(new Signal(k.getTwId(), SourceType.STEAM_KEYWORD, k.getKeyword(), null));
                    }
                }
            }
        }

        dtddSignalService.ifPresent(svc -> {
            TopicSignals topics = svc.getYesMostlySignals(in.igdbId(), in.sourceName());
            if (topics.topics().isEmpty()) return;
            Map<String, String> byLowered = new HashMap<>();
            topics.topics().forEach(t -> byLowered.put(t.toLowerCase(Locale.ROOT), t));
            Map<String, ?> severities = topics.severities();
            for (TwDtddTopicMapping m : dtddRepo.findAllByDtddTopicNameInIgnoreCase(byLowered.keySet())) {
                String topic = byLowered.getOrDefault(m.getDtddTopicName().toLowerCase(Locale.ROOT), m.getDtddTopicName());
                TwSeverityRule.Verdict verdict = TwSeverityRule.evaluate(m.getMinSeverity(), severities != null,
                        severities == null ? null : topics.severities().get(topic), dtddSeverityMinVotes);
                out.add(new Signal(m.getTwId(), SourceType.DTDD_TOPIC, topic, verdict));
            }
        });

        return new Explanation(out, steamLimitedUntil);
    }
}
