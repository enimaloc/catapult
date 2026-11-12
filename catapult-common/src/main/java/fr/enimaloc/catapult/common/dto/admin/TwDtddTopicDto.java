package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * A DTDD topic that turns a TW on: {@code minSeverity} its least severe level that does (null: any), and
 * {@code knownLevels} the topic's levels as the cached titles give them, mildest first (empty when none has them).
 */
public record TwDtddTopicDto(String topic, String minSeverity, List<String> knownLevels) {}
