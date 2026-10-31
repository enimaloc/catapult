package fr.enimaloc.catapult.repository.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TwitchatEventActionRepository extends JpaRepository<TwitchatEventAction, UUID> {

    List<TwitchatEventAction> findByUserOrderByPositionAsc(UserAccount user);

    List<TwitchatEventAction> findByUserAndEventTypeAndEnabledTrueOrderByPositionAsc(UserAccount user,
                                                                                      TwitchatNotificationEventType eventType);

    boolean existsByUser(UserAccount user);

    boolean existsByUserAndEventType(UserAccount user, TwitchatNotificationEventType eventType);

    /**
     * A bulk delete, run right away: Hibernate flushes inserts before deletes, so deleting the
     * entities would make re-inserting the same positions hit uq_twitchat_event_action_position.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TwitchatEventAction a where a.user = :user and a.eventType = :eventType")
    int deleteAllOfEvent(@Param("user") UserAccount user, @Param("eventType") TwitchatNotificationEventType eventType);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TwitchatEventAction a where a.user = :user")
    int deleteAllOfUser(@Param("user") UserAccount user);
}
