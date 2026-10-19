package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * What catapult-api answers when a trigger warning's saved Steam signals are tried against a Steam app:
 * the saved keywords found in the app's content-descriptor notes, the saved descriptor ids it carries,
 * whether the draft keyword would match (null when none was given or it is out of 2..50 chars), and the
 * notes themselves (lower-cased, may be empty).
 */
public record TwKeywordTestDto(List<String> matchedKeywords, List<Integer> matchedContentDescriptorIds,
                               Boolean draftKeywordMatch, String notes) {}
