package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;

public record TwitchatNotification(String message, String style, String icon, String authorName,
                                    List<TwitchatAction> actions) {
}
