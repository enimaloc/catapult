package fr.enimaloc.catapult.service.notification.dto;

// Exactly the shape of TwitchatAction (the wire DTO sent to Twitchat), but authored by the user
// as part of a preset. url and message may each contain {{action:TYPE}} placeholders resolved at
// render time (see TwitchatNotifier) — url for actionType "url" buttons, message for actionType
// "chat" buttons (or any other actionType Twitchat may interpret; Catapult passes actionType
// through verbatim and never special-cases it). Either field may be null if unused by this
// entry's actionType.
public record TwitchatRawAction(String label, String actionType, String url, String message, String theme) {
}
