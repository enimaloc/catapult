package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.http.HttpStatus;

/** Who may open the admin page, shared by its full-page and fragment routes. */
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
}
