package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.DtddProposalRequest;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.StatusResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.Authz;
import fr.enimaloc.catapult.service.dtdd.DtddGameSearchService;
import fr.enimaloc.catapult.service.dtdd.DtddMappingProposalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("dtdd.enabled")
public class ApiChannelDtddMappingController {

    private final DtddMappingProposalService proposals;
    private final DtddGameSearchService gameSearch;
    private final ApiUserResolver userResolver;

    @GetMapping(ApiPaths.INTERNAL + "/dtdd-mappings/{igdbId}")
    public StatusResponse status(@PathVariable("igdbId") String igdbId,
                                 @AuthenticationPrincipal Jwt jwt,
                                 Authentication authentication,
                                 HttpServletRequest request) {
        UserAccount user = jwt == null ? null : userResolver.viewer(jwt);
        return proposals.status(igdbId, user, Authz.isAdmin(authentication, request));
    }

    @PreAuthorize("@authz.isAdmin()")
    @PostMapping(ApiPaths.ADMIN + "/dtdd-mappings/{igdbId}/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validate(@PathVariable("igdbId") String igdbId) {
        proposals.validate(igdbId);
    }

    @PostMapping(ApiPaths.INTERNAL + "/dtdd-mappings/{igdbId}/proposals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void propose(@PathVariable("igdbId") String igdbId, @RequestBody DtddProposalRequest body,
                        @AuthenticationPrincipal Jwt jwt) {
        proposals.propose(igdbId, body, userResolver.viewer(jwt));
    }

    @GetMapping(ApiPaths.INTERNAL + "/dtdd-games")
    public SearchResponse search(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) return new SearchResponse(List.of());
        if (q.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query too long");
        return gameSearch.search(q);
    }

}
