package fr.enimaloc.catapult.common.dto.notification;

import java.util.Set;

/** The channels a member opted in to; none means the site only. */
public record NotificationChannelsDto(Set<NotificationChannel> channels) {

    public NotificationChannelsDto {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
    }
}
