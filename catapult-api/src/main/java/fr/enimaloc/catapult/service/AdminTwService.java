package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTwService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9_]{1,40}$");
    private static final int LABEL_MAX = 80;
    private static final int TOPIC_MAX = 200;
    private static final int KEYWORD_MAX = 50;
    private static final Set<Integer> STEAM_IDS = Set.of(1, 2, 3, 4, 5);

    private final TwDefinitionRepository defRepo;
    private final TwDtddTopicMappingRepository dtddRepo;
    private final TwIgdbDescriptorMappingRepository igdbRepo;
    private final TwSteamContentIdMappingRepository steamIdRepo;
    private final TwSteamKeywordRepository steamKwRepo;
    private final GameBindingRepository bindingRepo;
    private final ApplicationEventPublisher events;
    private final SteamStoreService steamStoreService;

    public List<TwDefinition> list() {
        return defRepo.findAllByOrderBySortOrderAscIdAsc();
    }

    public Optional<TwDefinition> get(String twId) {
        return defRepo.findById(twId);
    }

    @Transactional
    public TwDefinition create(String id, String label, String description, int sortOrder) {
        validateSlug(id);
        validateLabel(label);
        if (defRepo.existsById(id)) throw new IllegalStateException("Slug already exists: " + id);
        TwDefinition d = new TwDefinition();
        d.setId(id);
        d.setLabel(label.trim());
        d.setDescription(description);
        d.setSortOrder(sortOrder);
        d.setEnabled(true);
        TwDefinition saved = defRepo.save(d);
        events.publishEvent(new TwDefinitionsChangedEvent(this));
        return saved;
    }

    @Transactional
    public TwDefinition update(String id, String label, String description, Integer sortOrder, Boolean enabled) {
        TwDefinition d = defRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown TW: " + id));
        if (label != null)       { validateLabel(label); d.setLabel(label.trim()); }
        if (description != null) d.setDescription(description);
        if (sortOrder != null)   d.setSortOrder(sortOrder);
        if (enabled != null)     d.setEnabled(enabled);
        defRepo.save(d);
        events.publishEvent(new TwDefinitionsChangedEvent(this));
        return d;
    }

    @Transactional
    public void delete(String id) {
        if (bindingRepo.existsByTwsContaining(id))
            throw new IllegalStateException("TW still in use; soft-disable via enabled=false instead.");
        defRepo.deleteById(id);
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    @Transactional
    public void replaceDtddTopics(String twId, Set<String> topics) {
        requireExists(twId);
        dtddRepo.deleteAllByTwId(twId);
        for (String t : topics) {
            String trimmed = Objects.requireNonNull(t).trim();
            if (trimmed.isEmpty() || trimmed.length() > TOPIC_MAX) continue;
            TwDtddTopicMapping m = new TwDtddTopicMapping();
            m.setTwId(twId);
            m.setDtddTopicName(trimmed);
            dtddRepo.save(m);
        }
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    @Transactional
    public void replaceIgdbDescriptors(String twId, Set<Long> descriptorIds) {
        requireExists(twId);
        igdbRepo.deleteAllByTwId(twId);
        for (Long id : descriptorIds) {
            TwIgdbDescriptorMapping m = new TwIgdbDescriptorMapping();
            m.setTwId(twId);
            m.setDescriptorId(id);
            igdbRepo.save(m);
        }
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    @Transactional
    public void replaceSteamContentIds(String twId, Set<Integer> ids) {
        requireExists(twId);
        steamIdRepo.deleteAllByTwId(twId);
        for (Integer id : ids) {
            if (!STEAM_IDS.contains(id)) throw new IllegalArgumentException("Invalid Steam content id: " + id);
            TwSteamContentIdMapping m = new TwSteamContentIdMapping();
            m.setTwId(twId);
            m.setSteamContentId(id);
            steamIdRepo.save(m);
        }
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    @Transactional
    public void replaceSteamKeywords(String twId, Set<String> keywords) {
        requireExists(twId);
        steamKwRepo.deleteAllByTwId(twId);
        Set<String> seen = new HashSet<>();
        for (String k : keywords) {
            String norm = normalizeKeyword(k);
            if (norm == null || !seen.add(norm)) continue;
            TwSteamKeyword m = new TwSteamKeyword();
            m.setTwId(twId);
            m.setKeyword(norm);
            steamKwRepo.save(m);
        }
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    public List<String> listSteamKeywords(String twId) {
        requireExists(twId);
        return steamKwRepo.findAllByTwId(twId).stream()
            .map(TwSteamKeyword::getKeyword)
            .sorted()
            .toList();
    }

    @Transactional
    public void addSteamKeyword(String twId, String keyword) {
        requireExists(twId);
        String norm = normalizeKeyword(keyword);
        if (norm == null)
            throw new IllegalArgumentException("Invalid keyword; 2.." + KEYWORD_MAX + " chars required");
        boolean exists = steamKwRepo.findAllByTwId(twId).stream()
            .anyMatch(k -> k.getKeyword().equals(norm));
        if (exists) return;
        TwSteamKeyword m = new TwSteamKeyword();
        m.setTwId(twId);
        m.setKeyword(norm);
        steamKwRepo.save(m);
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    @Transactional
    public void removeSteamKeyword(String twId, String keyword) {
        requireExists(twId);
        String norm = normalizeKeyword(keyword);
        if (norm == null) return;
        steamKwRepo.findAllByTwId(twId).stream()
            .filter(k -> k.getKeyword().equals(norm))
            .forEach(steamKwRepo::delete);
        events.publishEvent(new TwDefinitionsChangedEvent(this));
    }

    /** Preview which of a TW's saved Steam signals match a given Steam app's store page. */
    public record SteamSignalTestResult(
        String notes, List<String> matchedKeywords, Boolean draftKeywordMatch,
        Set<Integer> matchedContentDescriptorIds) {}

    public SteamSignalTestResult testSteamSignals(String twId, String appId, String draftKeyword) {
        requireExists(twId);
        SteamStoreService.SteamTwSignals signals = steamStoreService.fetchTwSignals(List.of(appId))
            .getOrDefault(appId, SteamStoreService.SteamTwSignals.empty());
        String notes = signals.notesLowercase();

        List<String> matchedKeywords = steamKwRepo.findAllByTwId(twId).stream()
            .map(TwSteamKeyword::getKeyword)
            .filter(notes::contains)
            .sorted()
            .toList();

        String normDraft = normalizeKeyword(draftKeyword);
        Boolean draftMatch = normDraft == null ? null : notes.contains(normDraft);

        Set<Integer> matchedContentIds = steamIdRepo.findAllByTwId(twId).stream()
            .map(TwSteamContentIdMapping::getSteamContentId)
            .filter(signals.contentDescriptorIds()::contains)
            .collect(Collectors.toCollection(TreeSet::new));

        return new SteamSignalTestResult(notes, matchedKeywords, draftMatch, matchedContentIds);
    }

    private String normalizeKeyword(String k) {
        String norm = k == null ? null : k.toLowerCase(Locale.ROOT).trim();
        if (norm == null || norm.length() < 2 || norm.length() > KEYWORD_MAX) return null;
        return norm;
    }

    // --- validation ---
    private void validateSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches())
            throw new IllegalArgumentException("Invalid TW slug; expected ^[a-z0-9_]{1,40}$");
    }

    private void validateLabel(String label) {
        if (label == null || label.trim().isEmpty() || label.length() > LABEL_MAX)
            throw new IllegalArgumentException("Invalid label; 1.." + LABEL_MAX + " chars required");
    }

    private void requireExists(String twId) {
        if (!defRepo.existsById(twId)) throw new IllegalArgumentException("Unknown TW: " + twId);
    }
}
