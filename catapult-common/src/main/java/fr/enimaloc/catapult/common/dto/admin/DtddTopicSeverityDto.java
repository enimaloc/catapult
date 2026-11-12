package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * A cached topic's severity on a title: {@code levels} its labels, mildest first; {@code counts} and {@code percents}
 * the votes for each, by position; {@code total} the level votes; {@code verified} the reviewers' level (1-based),
 * null when they gave none.
 */
public record DtddTopicSeverityDto(List<String> levels, List<Integer> counts, List<Integer> percents, int total,
                                   Integer verified) {

    /** The label at {@code position} (1-based) among the levels, null out of them. */
    public String level(Integer position) {
        if (position == null || levels == null || position < 1 || position > levels.size()) return null;
        return levels.get(position - 1);
    }

    /** The reviewers' level as its label, null when they gave none. */
    public String verifiedLevel() {
        return level(verified);
    }
}
