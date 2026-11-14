package fr.enimaloc.catapult.service.ankama.haapi;

/** Ankama wants a Shield security code or certificate, or refused the code that was sent. */
public class AnkamaShieldException extends RuntimeException {

    public AnkamaShieldException(String message) {
        super(message);
    }
}
