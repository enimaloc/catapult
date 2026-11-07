package fr.enimaloc.catapult.repository.feature;

import fr.enimaloc.catapult.domain.feature.SeenFeature;
import fr.enimaloc.catapult.domain.feature.SeenFeatureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SeenFeatureRepository extends JpaRepository<SeenFeature, SeenFeatureId> {

    @Query("select s.id.featureKey from SeenFeature s where s.id.userId = :userId")
    List<String> findFeatureKeysByUserId(@Param("userId") UUID userId);

    /**
     * Records the feature as seen, unless it already is: the same tab opened from two devices at
     * once then both succeed, and the first time it was seen is the one kept.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO seen_feature (user_id, feature_key, seen_at) VALUES (:userId, :featureKey, :seenAt)"
            + " ON CONFLICT (user_id, feature_key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("featureKey") String featureKey,
                       @Param("seenAt") Instant seenAt);
}
