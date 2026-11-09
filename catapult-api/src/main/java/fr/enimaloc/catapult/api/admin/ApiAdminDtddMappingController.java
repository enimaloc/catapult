package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.RejectRequest;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal.Status;
import fr.enimaloc.catapult.service.dtdd.DtddMappingProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/dtdd-mapping-proposals")
@RequiredArgsConstructor
public class ApiAdminDtddMappingController {

    private final DtddMappingProposalService proposals;
    private final ApiUserResolver userResolver;

    /** The proposals in {@code status}, oldest first, as catapult-web reads them. */
    @GetMapping
    public List<DtddProposalDto> pending(@RequestParam(defaultValue = "PENDING") String status) {
        Status wanted;
        try {
            wanted = Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status " + status);
        }
        return proposals.proposals(wanted);
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        proposals.approve(id, userResolver.viewer(jwt));
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body,
                       @AuthenticationPrincipal Jwt jwt) {
        proposals.reject(id, userResolver.viewer(jwt));
    }
}
