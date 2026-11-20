package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * The OBS and Twitchat exchanges the logged-in member's page saw while they are in debug mode (debug-capture.js),
 * relayed to their debug journal in catapult-api with the page as their source: catapult-api's status (204 recorded,
 * 409 no longer in debug mode, the page then stopping its capture), 400 for a body that is no batch or is over
 * {@link ClientDebugBatches#MAX_BYTES}, 401 logged out. An admin impersonating a member gets a 409: their browser is
 * not the member's, nothing of it goes to the member's journal.
 */
@RestController
@RequiredArgsConstructor
public class ClientDebugController {
    private final ApiService apiService;

    @PostMapping("/me/debug/client-exchanges")
    public ResponseEntity<Void> record(HttpServletRequest request) {
        UUID me = apiService.currentUserId();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (ImpersonationController.impersonating(request)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Optional<ClientExchangeBatch> batch = ClientDebugBatches.read(request, "page");
        if (batch.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(apiService.postClientDebugExchanges(me, batch.get()).status()).build();
    }
}
