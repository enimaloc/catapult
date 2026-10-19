package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private final List<TwDefinitionDto> definitions = new ArrayList<>();

    MockTwStore() {
        definitions.add(new TwDefinitionDto("violence", "Violence", null, true, 10));
        definitions.add(new TwDefinitionDto("jumpscares", "Jump scares", null, true, 20));
        definitions.add(new TwDefinitionDto("flashing_lights", "Flashing lights", null, false, 30));
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
}
