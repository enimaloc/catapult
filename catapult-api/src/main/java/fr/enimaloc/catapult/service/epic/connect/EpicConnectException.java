package fr.enimaloc.catapult.service.epic.connect;

/** An Epic Connect connection failed or was ended by the server. Never carries the connection URL or a token. */
public class EpicConnectException extends RuntimeException {

    public EpicConnectException(String message) {
        super(message);
    }

    public EpicConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
