package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * The admin Notifications tab's actions (create, delete, Markdown preview), forwarded to catapult-api
 * with the session's JWT and answered with its status (502 when unreachable).
 */
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody AdminNotificationCreateBody body) {
        return relay(() -> apiService.adminCreateNotification(body));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return relay(() -> apiService.adminDeleteNotification(id));
    }

    /** The body's sanitized HTML as catapult-api renders it; 502 when it refuses or is unreachable. */
    @PostMapping("/preview")
    public ResponseEntity<String> preview(@RequestBody Map<String, String> request) {
        AdminAccess.require(filler);
        ApiClient.Result<String> result = apiService.adminPreviewNotification(request.get("body"));
        if (!result.ok()) return ResponseEntity.status(result.status()).build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(result.body());
    }

    private ResponseEntity<Void> relay(IntSupplier action) {
        AdminAccess.require(filler);
        return ResponseEntity.status(action.getAsInt()).build();
    }
}
