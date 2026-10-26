package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.repository.feature.SeenFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The new features (catapult-web's NewFeatures keys) a member has opened. */
@Service
@RequiredArgsConstructor
public class SeenFeatureService {

    private final SeenFeatureRepository seenFeatureRepository;

    /** The keys {@code userId} has opened. */
    public List<String> seen(UUID userId) {
        return seenFeatureRepository.findFeatureKeysByUserId(userId);
    }

    /** Records that {@code userId} opened {@code key}; a key already recorded stays as it is. */
    @Transactional
    public void markSeen(UUID userId, String key) {
        seenFeatureRepository.insertIfAbsent(userId, key, Instant.now());
    }
}
