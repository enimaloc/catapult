package fr.enimaloc.catapult.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** How an action forwarded to catapult-api answers the browser: with catapult-api's own outcome. */
final class ApiStatus {

    private ApiStatus() {
    }

    /**
     * 204 when catapult-api took the action (any 2xx); its error status when it refused it (4xx) or
     * failed (5xx); 502 for anything else, as when it could not be reached at all.
     */
    static ResponseEntity<Void> answer(int status) {
        if (status >= 200 && status < 300) {
            return ResponseEntity.noContent().build();
        }
        if (status >= 400 && status < 600) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
