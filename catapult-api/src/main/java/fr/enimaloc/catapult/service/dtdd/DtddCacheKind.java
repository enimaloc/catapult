package fr.enimaloc.catapult.service.dtdd;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** The DTDD caches the admin sees: a title's topics, the titles found, the searches made. */
public enum DtddCacheKind {
    TOPICS, GAMES, SEARCHES;

    /** The kind a route names ({@code topics}, {@code games}, {@code searches}); 404 for another. */
    public static DtddCacheKind of(String path) {
        try {
            return valueOf(path.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown DTDD cache");
        }
    }
}
