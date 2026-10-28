package fr.enimaloc.catapult.event.notification;

import java.util.Map;

/** A broadcast for every stream: catapult-api's event name and data, as published. */
public record BroadcastStreamEvent(String name, Map<String, Object> data) {}
