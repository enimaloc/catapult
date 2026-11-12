package fr.enimaloc.catapult.domain.dtdd;

import java.util.List;

/**
 * A topic's severity on a title, as cached: {@code levels} its labels, mildest first; {@code counts} and
 * {@code percents} the votes for each, by position; {@code total} the level votes; {@code verified} the reviewers'
 * level (1-based), null when they gave none.
 */
public record DtddTopicSeverity(List<String> levels, List<Integer> counts, List<Integer> percents, int total,
                                Integer verified) {}
