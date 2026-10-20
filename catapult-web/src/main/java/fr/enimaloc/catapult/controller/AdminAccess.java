package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.IntSupplier;

/** Who may open the admin page and use its actions, shared by its page, fragment and action routes. */
final class AdminAccess {

    private AdminAccess() {
    }

    /** 0 for an admin; else the status to refuse with: 401 when logged out, 403 for a member. */
    static int refusal(ModelFiller filler) {
        if (!ApiClient.hasSession()) {
            return HttpStatus.UNAUTHORIZED.value();
        }
        return filler.isAdmin() ? 0 : HttpStatus.FORBIDDEN.value();
    }

    /** Throws the refusal for a non-admin (see {@link #refusal}), for the admin action controllers. */
    static void require(ModelFiller filler) {
        int refused = refusal(filler);
        if (refused != 0) {
            throw new ResponseStatusException(HttpStatus.valueOf(refused));
        }
    }

    /** An admin action forwarded to catapult-api, answered with its status; refused first for a non-admin (see {@link #require}). */
    static ResponseEntity<Void> relay(ModelFiller filler, IntSupplier action) {
        return relay(filler, action, () -> { });
    }

    /**
     * An admin action forwarded to catapult-api, answered with its status; {@code onSuccess} runs after
     * a 2xx (a cache the change makes stale). Refused first for a non-admin (see {@link #require}).
     */
    static ResponseEntity<Void> relay(ModelFiller filler, IntSupplier action, Runnable onSuccess) {
        require(filler);
        int status = action.getAsInt();
        if (status >= 200 && status < 300) onSuccess.run();
        return ResponseEntity.status(status).build();
    }
}
