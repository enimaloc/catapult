package fr.enimaloc.catapult.event;

import java.util.List;
import java.util.UUID;

/** New notifications wait to be sent to these members' Twitchat; relayed to catapult-web once committed. */
public record NotificationDeliveryPendingEvent(List<UUID> userIds) {}
