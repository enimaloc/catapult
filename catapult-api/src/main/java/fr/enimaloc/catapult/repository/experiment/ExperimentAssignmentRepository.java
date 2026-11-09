package fr.enimaloc.catapult.repository.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, UUID> {
    Optional<ExperimentAssignment> findByExperimentAndUser(Experiment experiment, UserAccount user);

    @Query("""
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.experiment e
        JOIN FETCH a.variant
        WHERE a.user = :user AND e.status = fr.enimaloc.catapult.domain.experiment.Experiment.Status.ACTIVE
        """)
    List<ExperimentAssignment> findActiveByUser(@Param("user") UserAccount user);

    long countByExperimentAndVariant(Experiment experiment, ExperimentVariant variant);

    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user
        JOIN FETCH a.variant
        WHERE a.experiment = :experiment
        """,
        countQuery = "SELECT COUNT(a) FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    Page<ExperimentAssignment> findByExperiment(@Param("experiment") Experiment experiment, Pageable pageable);

    // The four shapes below (all / one variant, with / without a username filter) exist so that no query
    // binds a nullable parameter: PostgreSQL can't type an untyped null bind.

    /** One page of an experiment's participants, newest first. */
    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user
        JOIN FETCH a.variant
        WHERE a.experiment = :experiment
        ORDER BY a.assignedAt DESC, a.id ASC
        """,
        countQuery = "SELECT COUNT(a) FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    Page<ExperimentAssignment> searchAll(@Param("experiment") Experiment experiment, Pageable pageable);

    /** Like {@link #searchAll}, narrowed to one variant. */
    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user
        JOIN FETCH a.variant v
        WHERE a.experiment = :experiment AND v.id = :variantId
        ORDER BY a.assignedAt DESC, a.id ASC
        """,
        countQuery = "SELECT COUNT(a) FROM ExperimentAssignment a WHERE a.experiment = :experiment AND a.variant.id = :variantId")
    Page<ExperimentAssignment> searchByVariant(@Param("experiment") Experiment experiment,
                                               @Param("variantId") UUID variantId, Pageable pageable);

    /** Like {@link #searchAll}, narrowed to usernames matching {@code pattern} (lowercase, LIKE-escaped with a backslash). */
    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user u
        JOIN FETCH a.variant
        WHERE a.experiment = :experiment AND LOWER(u.twitchUsername) LIKE :pattern ESCAPE '\\'
        ORDER BY a.assignedAt DESC, a.id ASC
        """,
        countQuery = """
        SELECT COUNT(a) FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment AND LOWER(u.twitchUsername) LIKE :pattern ESCAPE '\\'
        """)
    Page<ExperimentAssignment> searchByUsername(@Param("experiment") Experiment experiment,
                                                @Param("pattern") String pattern, Pageable pageable);

    /** Narrowed to one variant and to usernames matching {@code pattern}. */
    @Query(value = """
        SELECT a FROM ExperimentAssignment a
        JOIN FETCH a.user u
        JOIN FETCH a.variant v
        WHERE a.experiment = :experiment AND v.id = :variantId AND LOWER(u.twitchUsername) LIKE :pattern ESCAPE '\\'
        ORDER BY a.assignedAt DESC, a.id ASC
        """,
        countQuery = """
        SELECT COUNT(a) FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment AND a.variant.id = :variantId AND LOWER(u.twitchUsername) LIKE :pattern ESCAPE '\\'
        """)
    Page<ExperimentAssignment> searchByVariantAndUsername(@Param("experiment") Experiment experiment,
                                                          @Param("variantId") UUID variantId,
                                                          @Param("pattern") String pattern, Pageable pageable);

    /** {@code [variantId, count]} rows; a variant nobody holds has no row. */
    @Query("SELECT a.variant.id, COUNT(a) FROM ExperimentAssignment a WHERE a.experiment = :experiment GROUP BY a.variant.id")
    List<Object[]> countByVariant(@Param("experiment") Experiment experiment);

    /** Every assignment of an experiment as a light projection: no user or variant entity is loaded. */
    @Query("""
        SELECT new fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRef(a.id, a.user.id, a.variant.id)
        FROM ExperimentAssignment a WHERE a.experiment = :experiment
        """)
    List<ExperimentAssignmentRef> findRefsByExperiment(@Param("experiment") Experiment experiment);

    /** Moves the given assignments to {@code variant} in one statement; callers chunk {@code ids}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ExperimentAssignment a SET a.variant = :variant WHERE a.id IN :ids")
    int moveToVariant(@Param("variant") ExperimentVariant variant, @Param("ids") Collection<UUID> ids);

    // The three shapes below back the "forced by a tag or group override" lookup: DB-side EXISTS, user ids only.

    /** Ids of the experiment's members holding the flag {@code flagKey}, whatever its value (null included). */
    @Query("""
        SELECT a.user.id FROM ExperimentAssignment a
        WHERE a.experiment = :experiment
          AND EXISTS (SELECT 1 FROM UserFlag f WHERE f.user = a.user AND f.flagKey = :flagKey)
        """)
    List<UUID> findUserIdsWithFlag(@Param("experiment") Experiment experiment, @Param("flagKey") String flagKey);

    /** Ids of the experiment's members whose flag {@code flagKey} equals {@code flagValue}. */
    @Query("""
        SELECT a.user.id FROM ExperimentAssignment a
        WHERE a.experiment = :experiment
          AND EXISTS (SELECT 1 FROM UserFlag f
                      WHERE f.user = a.user AND f.flagKey = :flagKey AND f.flagValue = :flagValue)
        """)
    List<UUID> findUserIdsWithFlagValue(@Param("experiment") Experiment experiment,
                                        @Param("flagKey") String flagKey, @Param("flagValue") String flagValue);

    /** Ids of the experiment's members belonging to the user group whose key is {@code groupKey}. */
    @Query("""
        SELECT a.user.id FROM ExperimentAssignment a
        WHERE a.experiment = :experiment
          AND EXISTS (SELECT 1 FROM UserGroup g JOIN g.members m WHERE g.key = :groupKey AND m.id = a.user.id)
        """)
    List<UUID> findUserIdsInGroup(@Param("experiment") Experiment experiment, @Param("groupKey") String groupKey);

    // The four shapes below pick who a notification to an experiment's participants reaches: members that are
    // active and not built-in accounts, all variants or one (again no nullable bind).

    /** The active, non-system members assigned to the experiment. */
    @Query("""
        SELECT u FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment
          AND u.status = fr.enimaloc.catapult.domain.account.UserAccount.Status.ACTIVE AND u.systemAccount = false
        """)
    List<UserAccount> findNotifiableUsers(@Param("experiment") Experiment experiment);

    /** Like {@link #findNotifiableUsers}, narrowed to one variant. */
    @Query("""
        SELECT u FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment AND a.variant.id = :variantId
          AND u.status = fr.enimaloc.catapult.domain.account.UserAccount.Status.ACTIVE AND u.systemAccount = false
        """)
    List<UserAccount> findNotifiableUsersByVariant(@Param("experiment") Experiment experiment,
                                                   @Param("variantId") UUID variantId);

    /** How many members {@link #findNotifiableUsers} returns. */
    @Query("""
        SELECT COUNT(a) FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment
          AND u.status = fr.enimaloc.catapult.domain.account.UserAccount.Status.ACTIVE AND u.systemAccount = false
        """)
    long countNotifiableUsers(@Param("experiment") Experiment experiment);

    /** How many members {@link #findNotifiableUsersByVariant} returns. */
    @Query("""
        SELECT COUNT(a) FROM ExperimentAssignment a JOIN a.user u
        WHERE a.experiment = :experiment AND a.variant.id = :variantId
          AND u.status = fr.enimaloc.catapult.domain.account.UserAccount.Status.ACTIVE AND u.systemAccount = false
        """)
    long countNotifiableUsersByVariant(@Param("experiment") Experiment experiment, @Param("variantId") UUID variantId);

    List<ExperimentAssignment> findAllByExperiment(Experiment experiment);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    void deleteAllByExperiment(@Param("experiment") Experiment experiment);

    void deleteByUser(UserAccount user);
}
