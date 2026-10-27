package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.RejectRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin DTDD mapping tab's actions (approve or reject a proposal), forwarded to catapult-api with
 * the session's JWT and answered with its status (404 unknown, 409 already resolved; 502 when unreachable).
 */
@RestController
@RequestMapping("/admin/dtdd-mapping/proposals/{id}")
@RequiredArgsConstructor
public class AdminDtddMappingController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        return AdminAccess.relay(filler, () -> apiService.adminApproveDtddProposal(id));
    }

    @PostMapping("/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body) {
        return AdminAccess.relay(filler, () -> apiService.adminRejectDtddProposal(id, body == null ? null : body.reason()));
    }
}
