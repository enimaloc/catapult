package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin Keys tab's Bungie.net card (set, remove), forwarded to catapult-api with the session's JWT and answered
 * with its status (204; 400 for a malformed value; 502 when unreachable). The secret and the key travel in the body
 * and are never logged; {@link SaveBody#toString()} never shows them.
 */
@RestController
@RequestMapping("/admin/bungie-credentials")
@RequiredArgsConstructor
public class AdminBungieCredentialsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record SaveBody(String clientId, String clientSecret, String apiKey) {
        @Override
        public String toString() {
            return "SaveBody[clientId=" + clientId + ", clientSecret=***, apiKey=***]";
        }
    }

    @PostMapping("/save")
    public ResponseEntity<Void> save(@RequestBody SaveBody body) {
        AdminAccess.require(filler);
        String clientId = body.clientId() == null ? "" : body.clientId().trim();
        if (clientId.isEmpty()) return ResponseEntity.badRequest().build();
        return AdminAccess.relay(filler, () -> apiService.adminSaveBungieCredentials(clientId,
                body.clientSecret() == null ? "" : body.clientSecret().trim(),
                body.apiKey() == null ? "" : body.apiKey().trim()));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete() {
        AdminAccess.require(filler);
        return AdminAccess.relay(filler, apiService::adminDeleteBungieCredentials);
    }
}
