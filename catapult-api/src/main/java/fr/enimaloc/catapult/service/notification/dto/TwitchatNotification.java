package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;

public record TwitchatNotification(String message, String style, List<TwitchatAction> actions) {
}
