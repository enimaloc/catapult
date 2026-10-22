package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountDto;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountRequest;
import fr.enimaloc.catapult.common.dto.admin.NotificationSentDto;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The admin Notifications tab's actions (create, count an audience, delete, Markdown preview), forwarded to catapult-api
 * with the session's JWT and answered with its status (502 when unreachable).
 */
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    /** catapult-api's status; with {@code {"sent": n}} when the audience was reached by one send (experiment participants). */
    @PostMapping
    public ResponseEntity<NotificationSentDto> create(@RequestBody AdminNotificationCreateBody body) {
        AdminAccess.require(filler);
        ApiClient.Result<Integer> result = apiService.adminSendNotification(body);
        if (!result.ok() || result.body() == null) return ResponseEntity.status(result.status()).build();
        return ResponseEntity.status(result.status()).body(new NotificationSentDto(result.body()));
    }

    /** How many members the experiment audience in {@code request} reaches; 503 when catapult-api fails or refuses. */
    @PostMapping("/audience-count")
    public ResponseEntity<AudienceCountDto> audienceCount(@RequestBody AudienceCountRequest request) {
        AdminAccess.require(filler);
        Long count = apiService.adminNotificationAudienceCount(request.experimentId(), request.variantId());
        if (count == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok(new AudienceCountDto(count));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return AdminAccess.relay(filler, () -> apiService.adminDeleteNotification(id));
    }

    /** The body's sanitized HTML as catapult-api renders it; 502 when it refuses or is unreachable. */
    @PostMapping("/preview")
    public ResponseEntity<String> preview(@RequestBody Map<String, String> request) {
        AdminAccess.require(filler);
        ApiClient.Result<String> result = apiService.adminPreviewNotification(request.get("body"));
        if (!result.ok()) return ResponseEntity.status(result.status()).build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(result.body());
    }
}
