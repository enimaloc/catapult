package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.DtddTopicsCache;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics;
import fr.enimaloc.catapult.repository.DtddTopicsCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class DtddService {

    private final DtddMappingService mappingService;
    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddApiClient apiClient;

    @Setter @Value("${dtdd.cache.topics-ttl-hours:168}") private int topicsTtlHours;

    @Transactional
    public Optional<DtddTopics> getTopics(String igdbId, String name) {
        DtddGameMapping mapping = mappingService.resolve(igdbId, name);
        if (mapping.getDtddId() == null) return Optional.empty();
        Long dtddId = mapping.getDtddId();

        Optional<DtddTopicsCache> cached = topicsRepo.findById(dtddId);
        if (cached.isPresent()) {
            DtddTopicsCache c = cached.get();
            if (!isFresh(c.getFetchedAt())) refreshAsync(dtddId);
            return Optional.of(new DtddTopics(c.getYesTopics(), c.getNoTopics(), c.getMostlyTopics()));
        }

        Optional<DtddTopics> remote = apiClient.fetchTopics(dtddId);
        remote.ifPresent(t -> persist(dtddId, t));
        return remote;
    }

    @Async
    void refreshAsync(long dtddId) {
        try {
            apiClient.fetchTopics(dtddId).ifPresent(t -> persist(dtddId, t));
        } catch (Exception e) {
            log.warn("DTDD async refresh failed for {}: {}", dtddId, e.getMessage());
        }
    }

    private void persist(long dtddId, DtddTopics t) {
        topicsRepo.save(new DtddTopicsCache(
            dtddId, t.yesTopics(), t.noTopics(), t.mostlyTopics(), Instant.now()));
    }

    private boolean isFresh(Instant ts) {
        return ts != null && ts.isAfter(Instant.now().minus(Duration.ofHours(topicsTtlHours)));
    }
}
