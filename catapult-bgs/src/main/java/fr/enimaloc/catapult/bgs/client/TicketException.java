package fr.enimaloc.catapult.bgs.client;

/** Ticket minting failed. The message never contains a ticket. */
public class TicketException extends Exception {

    private final boolean codeRequired;

    public TicketException(String message, boolean codeRequired) {
        super(message);
        this.codeRequired = codeRequired;
    }

    public TicketException(String message, boolean codeRequired, Throwable cause) {
        super(message, cause);
        this.codeRequired = codeRequired;
    }

    /** True when the login asks for a 2FA code: retrying without a human is pointless. */
    public boolean codeRequired() {
        return codeRequired;
    }
}
