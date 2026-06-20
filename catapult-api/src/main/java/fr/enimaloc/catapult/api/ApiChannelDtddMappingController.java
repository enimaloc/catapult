package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.domain.DtddMappingProposal.Status;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/channel/dtdd-mapping")
@RequiredArgsConstructor
public class ApiChannelDtddMappingController {

    private final DtddGameMappingRepository mappingRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final DtddMappingProposalRepository proposalRepo;
    private final DtddApiClient apiClient;

    @GetMapping
    public StatusResponse status(@RequestParam("igdbId") String igdbId,
                                 @AuthenticationPrincipal UserAccount user) {
        Optional<DtddGameMapping> mappingOpt = mappingRepo.findById(igdbId);
        Optional<DtddMappingProposal> myProposal = user == null ? Optional.empty()
            : proposalRepo.findFirstByProposerAndIgdbIdAndStatus(user, igdbId, Status.PENDING);
        long otherPending = proposalRepo.countByIgdbIdAndStatus(igdbId, Status.PENDING);
        boolean canValidate = mappingOpt.isPresent()
            && !mappingOpt.get().isVerified()
            && otherPending == 0;

        MappingDto current = mappingOpt.map(m -> {
            String name = null;
            if (m.getDtddId() != null) {
                name = gameRepo.findById(m.getDtddId()).map(DtddGameCache::getName).orElse(null);
            }
            return new MappingDto(m.getDtddId(), name, m.getConfidence(), m.isVerified());
        }).orElse(null);

        ProposalDto pending = myProposal.map(p ->
            new ProposalDto(p.getId(), p.getProposedDtddId(), p.getReason())).orElse(null);
        return new StatusResponse(current, pending, canValidate);
    }

    @PostMapping("/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void validate(@RequestBody ValidateRequest body, @AuthenticationPrincipal UserAccount user) {
        DtddGameMapping mapping = mappingRepo.findById(body.igdbId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (mapping.isVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mapping already verified");
        }
        if (proposalRepo.countByIgdbIdAndStatus(body.igdbId(), Status.PENDING) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pending proposals exist");
        }
        mapping.setVerified(true);
        mapping.setConfidence(1.0);
        mappingRepo.save(mapping);
    }

    @PostMapping("/propose")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void propose(@RequestBody ProposeRequest body, @AuthenticationPrincipal UserAccount user) {
        DtddMappingProposal p = new DtddMappingProposal();
        p.setId(UUID.randomUUID());
        p.setIgdbId(body.igdbId());
        p.setProposedDtddId(body.dtddId());
        p.setProposer(user);
        p.setReason(body.reason());
        p.setStatus(Status.PENDING);
        p.setCreatedAt(Instant.now());
        proposalRepo.save(p);
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) return new SearchResponse(List.of());
        if (q.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query too long");

        String norm = q.toLowerCase(Locale.ROOT).trim();
        Map<Long, SearchResultDto> dedup = new LinkedHashMap<>();

        for (DtddGameCache g : gameRepo.searchByNameLike(norm, PageRequest.of(0, 20))) {
            dedup.put(g.getDtddId(),
                new SearchResultDto(g.getDtddId(), g.getName(), g.getMediaType(), g.getPosterUrl()));
        }

        Optional<DtddSearchCache> sc = searchRepo.findById(norm);
        if (sc.isEmpty()) {
            apiClient.search(q).ifPresent(remote -> {
                Instant now = Instant.now();
                for (DtddSearchResult r : remote) {
                    gameRepo.save(new DtddGameCache(r.dtddId(), r.name(), r.slug(), r.mediaType(), r.posterUrl(), now));
                    dedup.putIfAbsent(r.dtddId(),
                        new SearchResultDto(r.dtddId(), r.name(), r.mediaType(), r.posterUrl()));
                }
                searchRepo.save(new DtddSearchCache(norm,
                    remote.stream().map(DtddSearchResult::dtddId).toList(), now));
            });
        }

        return new SearchResponse(new ArrayList<>(dedup.values()));
    }

    public record StatusResponse(MappingDto current, ProposalDto myPendingProposal, boolean canValidateDirectly) {}
    public record MappingDto(Long dtddId, String name, double confidence, boolean verified) {}
    public record ProposalDto(UUID id, Long proposedDtddId, String reason) {}
    public record ValidateRequest(String igdbId) {}
    public record ProposeRequest(String igdbId, Long dtddId, String reason) {}
    public record SearchResponse(List<SearchResultDto> results) {}
    public record SearchResultDto(long dtddId, String name, String mediaType, String posterUrl) {}
}
