package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin Broadcast tab's action, forwarded to catapult-api with the session's JWT and answered
 * with its status (202 sent, 400 refused, 429 rate-limited, 502 unreachable). A body whose
 * {@code name} is not a broadcast type is refused here with 400, the API never called.
 */
@RestController
@RequestMapping("/admin/broadcast")
@RequiredArgsConstructor
public class AdminBroadcastController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping
    public ResponseEntity<Void> send(@RequestBody BroadcastRequestDto body) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminBroadcast(body)).build();
    }

    /** An unknown or missing {@code name}, or a malformed field: Jackson refused the body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> unreadable() {
        return ResponseEntity.badRequest().build();
    }
}
