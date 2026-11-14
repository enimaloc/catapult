package fr.enimaloc.catapult.service.ankama.haapi;

/** Ankama refused the API key or the refresh token (HTTP 401/403). Never carries a credential. */
public class AnkamaAuthException extends RuntimeException {

    public AnkamaAuthException(String message) {
        super(message);
    }
}
