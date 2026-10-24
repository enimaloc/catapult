package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameBindingRepository extends JpaRepository<GameBinding, UUID> {

    Optional<GameBinding> findByUserAndSourceIdAndSourceType(
        UserAccount user, String sourceId, GameBinding.SourceType sourceType
    );

    Page<GameBinding> findByUser(UserAccount user, Pageable pageable);

    Page<GameBinding> findByUserAndStatusIn(
        UserAccount user, java.util.Collection<GameBinding.Status> statuses, Pageable pageable
    );

    Page<GameBinding> findByUserAndSourceType(UserAccount user, GameBinding.SourceType sourceType, Pageable pageable);

    List<GameBinding> findByUser(UserAccount user);

    Optional<GameBinding> findByIdAndUser(UUID id, UserAccount user);

    /**
     * Most recently touched binding of one of the given source types — used to fall back to "the
     * last game they played" for a Swagger "try it out" example when nothing is currently detected
     * (see ApiChannelDataController).
     */
    Optional<GameBinding> findFirstByUserAndSourceTypeInOrderByUpdatedAtDesc(
        UserAccount user, java.util.Collection<GameBinding.SourceType> sourceTypes
    );

    void deleteByUser(UserAccount user);

    List<GameBinding> findAllByStatusAndIgnoredFalse(GameBinding.Status status);

    /** Every streamer's binding of one game in the given statuses — the ones an admin game mapping applies to. */
    List<GameBinding> findAllBySourceTypeAndSourceIdAndStatusIn(
        GameBinding.SourceType sourceType, String sourceId, java.util.Collection<GameBinding.Status> statuses
    );

    /**
     * {@link #findAllBySourceTypeAndSourceIdAndStatusIn}, the rows locked for update until the transaction ends: an
     * admin game mapping applied to them cannot overwrite a streamer's concurrent MANUAL choice (its status is
     * re-checked once the lock is held).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("""
        SELECT b FROM GameBinding b
        WHERE b.sourceType = :sourceType AND b.sourceId = :sourceId AND b.status IN :statuses
        """)
    List<GameBinding> lockAllBySourceTypeAndSourceIdAndStatusIn(
        @org.springframework.data.repository.query.Param("sourceType") GameBinding.SourceType sourceType,
        @org.springframework.data.repository.query.Param("sourceId") String sourceId,
        @org.springframework.data.repository.query.Param("statuses") java.util.Collection<GameBinding.Status> statuses);

    /**
     * The detected games (platform + ID, MANUAL platform left out) with no admin game mapping and at least one
     * INCOMPLETE binding, as rows {@code [source_type, source_id, streamer count]}: most streamers first, then by name.
     * The streamers counted are those a mapping would change: MANUAL bindings are left out.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT b.source_type, b.source_id, COUNT(DISTINCT b.user_id) FROM game_binding b
        WHERE b.source_type <> 'MANUAL' AND b.source_id IS NOT NULL AND b.status <> 'MANUAL'
          AND NOT EXISTS (SELECT 1 FROM game_mapping m
                          WHERE m.source_type = b.source_type AND m.source_id = b.source_id)
        GROUP BY b.source_type, b.source_id
        HAVING SUM(CASE WHEN b.status = 'INCOMPLETE' THEN 1 ELSE 0 END) > 0
        ORDER BY COUNT(DISTINCT b.user_id) DESC, MIN(LOWER(b.source_name)), b.source_id
        """,
        countQuery = """
        SELECT COUNT(*) FROM (
          SELECT 1 FROM game_binding b
          WHERE b.source_type <> 'MANUAL' AND b.source_id IS NOT NULL AND b.status <> 'MANUAL'
            AND NOT EXISTS (SELECT 1 FROM game_mapping m
                            WHERE m.source_type = b.source_type AND m.source_id = b.source_id)
          GROUP BY b.source_type, b.source_id
          HAVING SUM(CASE WHEN b.status = 'INCOMPLETE' THEN 1 ELSE 0 END) > 0
        ) missing
        """,
        nativeQuery = true)
    Page<Object[]> findGamesMissingAMapping(Pageable pageable);

    /**
     * The games detected on a platform whose ID starts with {@code idPrefix} or one of whose names matches
     * {@code namePattern} (both lowercase LIKE patterns, escaped with a backslash), as rows
     * {@code [sourceType, sourceId, streamer count]}: most streamers first.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT b.sourceType, b.sourceId, COUNT(DISTINCT b.user.id) FROM GameBinding b
        WHERE b.sourceType = :sourceType AND b.sourceId IS NOT NULL
          AND EXISTS (SELECT 1 FROM GameBinding n
                      WHERE n.sourceType = b.sourceType AND n.sourceId = b.sourceId
                        AND (LOWER(n.sourceId) LIKE :idPrefix ESCAPE '\\'
                             OR LOWER(n.sourceName) LIKE :namePattern ESCAPE '\\'))
        GROUP BY b.sourceType, b.sourceId
        ORDER BY COUNT(DISTINCT b.user.id) DESC, b.sourceId
        """)
    List<Object[]> findDetectedGames(@org.springframework.data.repository.query.Param("sourceType") GameBinding.SourceType sourceType,
                                     @org.springframework.data.repository.query.Param("idPrefix") String idPrefix,
                                     @org.springframework.data.repository.query.Param("namePattern") String namePattern,
                                     Pageable pageable);

    /**
     * How many bindings carry each detected name of the games on these platforms with these IDs, as rows
     * {@code [sourceType, sourceId, sourceName, count]}: what picks a game's most frequent name. Any platform / ID
     * pair of the two collections may come back; the platform keeps the query on the (source_type, source_id) index.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT b.sourceType, b.sourceId, b.sourceName, COUNT(b) FROM GameBinding b
        WHERE b.sourceType IN :sourceTypes AND b.sourceId IN :sourceIds
        GROUP BY b.sourceType, b.sourceId, b.sourceName
        """)
    List<Object[]> countSourceNames(
        @org.springframework.data.repository.query.Param("sourceTypes") java.util.Collection<GameBinding.SourceType> sourceTypes,
        @org.springframework.data.repository.query.Param("sourceIds") java.util.Collection<String> sourceIds);

    /**
     * How many bindings of the games on these platforms with these IDs have the status, as rows
     * {@code [sourceType, sourceId, count]}; as {@link #countSourceNames}, any pair of the two collections may come back.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT b.sourceType, b.sourceId, COUNT(b) FROM GameBinding b
        WHERE b.sourceType IN :sourceTypes AND b.sourceId IN :sourceIds AND b.status = :status
        GROUP BY b.sourceType, b.sourceId
        """)
    List<Object[]> countByGameAndStatus(
        @org.springframework.data.repository.query.Param("sourceTypes") java.util.Collection<GameBinding.SourceType> sourceTypes,
        @org.springframework.data.repository.query.Param("sourceIds") java.util.Collection<String> sourceIds,
        @org.springframework.data.repository.query.Param("status") GameBinding.Status status);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(b) > 0 FROM GameBinding b JOIN b.tws t WHERE t = :twId")
    boolean existsByTwsContaining(@org.springframework.data.repository.query.Param("twId") String twId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT b FROM GameBinding b WHERE b.ignored = false " +
        "AND b.twOverride = false AND b.status IN ('AUTO','MAPPED','MANUAL') " +
        "AND SIZE(b.tws) = 0")
    org.springframework.data.domain.Page<GameBinding> findCandidatesForTwBackfill(
        org.springframework.data.domain.Pageable pageable);

    /**
     * Wider net than {@link #findCandidatesForTwBackfill} — includes bindings
     * that already have a TW set, as long as the streamer hasn't pinned it
     * manually. Drives the admin "rebuild TW suggestions" action.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT b FROM GameBinding b WHERE b.ignored = false " +
        "AND b.twOverride = false AND b.status IN ('AUTO','MAPPED','MANUAL')")
    org.springframework.data.domain.Page<GameBinding> findCandidatesForTwRebuild(
        org.springframework.data.domain.Pageable pageable);

    /**
     * Widest net — includes bindings the streamer has pinned manually
     * ({@code twOverride=true}). Drives the admin "force-rebuild, override
     * pinned choices" action; use with care.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT b FROM GameBinding b WHERE b.ignored = false " +
        "AND b.status IN ('AUTO','MAPPED','MANUAL')")
    org.springframework.data.domain.Page<GameBinding> findAllCandidatesForTwForceRebuild(
        org.springframework.data.domain.Pageable pageable);
}
