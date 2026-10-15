package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
}
