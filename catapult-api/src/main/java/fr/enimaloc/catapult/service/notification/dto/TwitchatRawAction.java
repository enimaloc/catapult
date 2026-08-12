package fr.enimaloc.catapult.service.notification.dto;

// Exactly the shape of TwitchatAction (the wire DTO sent to Twitchat), but authored by the user
// as part of a preset — url may contain {{action:TYPE}} placeholders resolved at render time
// (see TwitchatNotifier), or be a fully static/custom URL.
public record TwitchatRawAction(String label, String actionType, String url, String theme) {
}
