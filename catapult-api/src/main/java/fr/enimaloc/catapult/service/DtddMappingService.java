package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.DtddGameCache;
import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.DtddSearchCache;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.DtddSearchCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RefreshScope
@ConditionalOnBooleanProperty("dtdd.enabled")
@RequiredArgsConstructor
public class DtddMappingService {

    private final DtddGameMappingRepository mappingRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final DtddApiClient apiClient;

    @Setter @Value("${dtdd.cache.search-ttl-days:30}")     private int searchTtlDays;
    @Setter @Value("${dtdd.match.min-confidence:0.85}")    private double minConfidence;
    @Setter @Value("${dtdd.match.min-candidate-score:0.30}") private double minCandidateScore;
    @Setter @Value("${dtdd.match.weight-name:0.7}")        private double weightName;
    @Setter @Value("${dtdd.match.weight-media-type:0.3}")  private double weightMediaType;

    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    @Transactional
    public DtddGameMapping resolve(String igdbId, String name) {
        Optional<DtddGameMapping> existing = mappingRepo.findById(igdbId);
        if (existing.isPresent()) return existing.get();

        String q = normalize(name);
        List<DtddSearchResult> candidates = candidatesFromCacheOrApi(q, name);
        if (candidates == null) {
            // API unavailable + no cache → don't persist, return ephemeral negative
            return new DtddGameMapping(igdbId, null, 0.0, false, Instant.now());
        }

        DtddSearchResult best = null;
        double bestScore = -1;
        for (DtddSearchResult c : candidates) {
            double s = score(name, c);
            if (s > bestScore) { bestScore = s; best = c; }
        }

        DtddGameMapping mapping = new DtddGameMapping();
        mapping.setIgdbId(igdbId);
        mapping.setResolvedAt(Instant.now());
        if (best != null && bestScore >= minCandidateScore) {
            mapping.setDtddId(best.dtddId());
            mapping.setConfidence(bestScore);
            mapping.setVerified(bestScore >= minConfidence);
        } else {
            mapping.setDtddId(null);
            mapping.setConfidence(0.0);
            mapping.setVerified(false);
        }
        return mappingRepo.save(mapping);
    }

    /** @return null if API failed AND no cache. Empty list if API returned nothing. */
    private List<DtddSearchResult> candidatesFromCacheOrApi(String q, String rawQuery) {
        Optional<DtddSearchCache> cached = searchRepo.findById(q);
        if (cached.isPresent() && isFresh(cached.get().getSearchedAt(), Duration.ofDays(searchTtlDays))) {
            List<DtddGameCache> games = gameRepo.findAllById(cached.get().getDtddIds());
            return games.stream().map(g -> new DtddSearchResult(
                g.getDtddId(), g.getName(), g.getSlug(), g.getMediaType(), g.getPosterUrl()
            )).toList();
        }

        Optional<List<DtddSearchResult>> remote = apiClient.search(rawQuery);
        if (remote.isEmpty()) return null;

        Instant now = Instant.now();
        for (DtddSearchResult r : remote.get()) {
            DtddGameCache g = new DtddGameCache(r.dtddId(), r.name(), r.slug(), r.mediaType(), r.posterUrl(), now);
            gameRepo.save(g);
        }
        DtddSearchCache cache = new DtddSearchCache(q, remote.get().stream().map(DtddSearchResult::dtddId).toList(), now);
        searchRepo.save(cache);
        return remote.get();
    }

    double score(String queryName, DtddSearchResult candidate) {
        double sim = normalizedLevenshtein(normalize(queryName), normalize(candidate.name()));
        double mediaBoost = "Video Game".equalsIgnoreCase(candidate.mediaType()) ? 1.0 : 0.0;
        return weightName * sim + weightMediaType * mediaBoost;
    }

    static double normalizedLevenshtein(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return 1.0 - ((double) dist / max);
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static boolean isFresh(Instant ts, Duration ttl) {
        return ts != null && ts.isAfter(Instant.now().minus(ttl));
    }
}
