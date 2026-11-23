package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin Keys tab's osu! card (set, remove), forwarded to catapult-api with the session's JWT and answered with its
 * status (204; 400 for a malformed value; 502 when unreachable). The secret travels in the body and is never logged;
 * {@link SaveBody#toString()} never shows it.
 */
@RestController
@RequestMapping("/admin/osu-credentials")
@RequiredArgsConstructor
public class AdminOsuCredentialsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record SaveBody(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "SaveBody[clientId=" + clientId + ", clientSecret=***]";
        }
    }

    @PostMapping("/save")
    public ResponseEntity<Void> save(@RequestBody SaveBody body) {
        AdminAccess.require(filler);
        String clientId = body.clientId() == null ? "" : body.clientId().trim();
        if (clientId.isEmpty()) return ResponseEntity.badRequest().build();
        return AdminAccess.relay(filler, () -> apiService.adminSaveOsuCredentials(clientId,
                body.clientSecret() == null ? "" : body.clientSecret().trim()));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete() {
        AdminAccess.require(filler);
        return AdminAccess.relay(filler, apiService::adminDeleteOsuCredentials);
    }
}
