package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.NotificationRecipient;
import fr.enimaloc.catapult.domain.NotificationRecipientId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, NotificationRecipientId> {
    Page<NotificationRecipient> findByUserIdOrderByNotificationCreatedAtDesc(UUID userId, Pageable pageable);
    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("update NotificationRecipient nr set nr.readAt = :now where nr.user.id = :userId and nr.readAt is null")
    int markAllReadForUser(UUID userId, Instant now);
}
