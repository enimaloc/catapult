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

    List<ExperimentAssignment> findAllByExperiment(Experiment experiment);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExperimentAssignment a WHERE a.experiment = :experiment")
    void deleteAllByExperiment(@Param("experiment") Experiment experiment);

    void deleteByUser(UserAccount user);
}
