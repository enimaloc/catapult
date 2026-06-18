package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.service.notification.NotificationDto;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

@Getter
public class NotificationCreatedEvent extends ApplicationEvent {
    private final List<UUID> recipientUserIds;
    private final NotificationDto dto;

    public NotificationCreatedEvent(Object source, List<UUID> recipientUserIds, NotificationDto dto) {
        super(source);
        this.recipientUserIds = recipientUserIds;
        this.dto = dto;
    }
}
