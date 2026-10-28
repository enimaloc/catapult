package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.TwKeywordTestDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The mock's trigger warning definitions: one store for the whole application. Answers like
 * catapult-api: 201 for a created definition (400 invalid id or label, 409 id already used), listed by
 * sort order then id.
 */
class MockTwStore {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final int LABEL_MAX = 80;

    private static final int KEYWORD_MAX = 50;
    /** The one Steam app the mock knows notes for. */
    private static final String KNOWN_APP = "730";
    private static final String KNOWN_NOTES = "intense violence, blood and gore";

    private final List<TwDefinitionDto> definitions = new ArrayList<>();
    private final Map<String, TreeSet<String>> keywords = new java.util.HashMap<>();

    MockTwStore() {
        definitions.add(new TwDefinitionDto("violence", "Violence", null, true, 10));
        definitions.add(new TwDefinitionDto("jumpscares", "Jump scares", null, true, 20));
        definitions.add(new TwDefinitionDto("flashing_lights", "Flashing lights", null, false, 30));
        keywords.put("violence", new TreeSet<>(List.of("blood", "gore")));
    }

    synchronized List<TwDefinitionDto> list() {
        return definitions.stream()
                .sorted(Comparator.comparingInt(TwDefinitionDto::sortOrder).thenComparing(TwDefinitionDto::id))
                .toList();
    }

    synchronized Optional<TwDefinitionDto> find(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    synchronized int create(String id, String label, int sortOrder) {
        if (id == null || !ID.matcher(id).matches()) return 400;
        if (label == null || label.isBlank() || label.trim().length() > LABEL_MAX) return 400;
        if (definitions.stream().anyMatch(d -> d.id().equals(id))) return 409;
        definitions.add(new TwDefinitionDto(id, label.trim(), null, true, sortOrder));
        return 201;
    }

    /** The rebuild runs in the background in catapult-api: accepted, nothing to change here. */
    int rebuild() {
        return 202;
    }

    /** A definition's keywords, sorted; null when the definition is unknown. */
    synchronized List<String> keywords(String id) {
        return find(id).isEmpty() ? null : List.copyOf(keywords.getOrDefault(id, new TreeSet<>()));
    }

    /** 204 like catapult-api (lower-cased, trimmed, a known one changes nothing), 400 outside 2..50 chars, 404 unknown. */
    synchronized int addKeyword(String id, String keyword) {
        if (find(id).isEmpty()) return 404;
        String norm = normalize(keyword);
        if (norm == null) return 400;
        keywords.computeIfAbsent(id, k -> new TreeSet<>()).add(norm);
        return 204;
    }

    synchronized int removeKeyword(String id, String keyword) {
        if (find(id).isEmpty()) return 404;
        String norm = normalize(keyword);
        if (norm != null && keywords.containsKey(id)) keywords.get(id).remove(norm);
        return 204;
    }

    /**
     * Tries the saved keywords and the draft on a Steam app: the mock knows the notes of app 730 only
     * (any other app has none). Null when the definition is unknown.
     */
    synchronized TwKeywordTestDto test(String id, String appId, String draftKeyword) {
        if (find(id).isEmpty()) return null;
        String notes = KNOWN_APP.equals(appId) ? KNOWN_NOTES : "";
        List<String> matched = keywords.getOrDefault(id, new TreeSet<>()).stream().filter(notes::contains).toList();
        String draft = normalize(draftKeyword);
        return new TwKeywordTestDto(matched, List.of(), draft == null ? null : notes.contains(draft), notes);
    }

    private static String normalize(String keyword) {
        String norm = keyword == null ? null : keyword.toLowerCase(Locale.ROOT).trim();
        return norm == null || norm.length() < 2 || norm.length() > KEYWORD_MAX ? null : norm;
    }
}
