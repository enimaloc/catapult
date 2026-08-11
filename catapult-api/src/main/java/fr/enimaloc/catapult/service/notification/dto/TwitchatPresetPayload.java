package fr.enimaloc.catapult.service.notification.dto;

import java.util.Map;

// Jackson-deserialized shape of TwitchatPayloadPreset.payloadJson. All fields optional except
// message (enforced by TwitchatPayloadPresetService at save time, not here) — a missing/blank
// field falls back to TwitchatDefaultPayloads at render time. `actions` keys are
// TwitchatActionType names (e.g. "REVERT_CATEGORY"); an entry for an action type that isn't
// applicable to the current notification is simply never looked up.
public record TwitchatPresetPayload(String message, String style, String icon, String authorName,
                                     Map<String, TwitchatActionOverride> actions) {
}
