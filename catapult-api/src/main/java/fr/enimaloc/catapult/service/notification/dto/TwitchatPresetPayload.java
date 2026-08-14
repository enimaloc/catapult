package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;

// Jackson-deserialized shape of TwitchatPayloadPreset.payloadJson. message/style/icon/authorName
// are all optional (enforced non-blank only for message, by TwitchatPayloadPresetService at save
// time) — a missing/blank one falls back to TwitchatDefaultPayloads at render time. `actions` is
// the raw actions array sent to Twitchat verbatim, except for {{action:TYPE}} placeholder
// resolution in each entry's `url` (see TwitchatNotifier). If `actions` is null, the notification
// carries no action buttons — the preset owns the array completely, there is no per-entry
// fallback to defaults.
public record TwitchatPresetPayload(String message, String style, String icon, String authorName,
                                     List<TwitchatRawAction> actions) {
}
