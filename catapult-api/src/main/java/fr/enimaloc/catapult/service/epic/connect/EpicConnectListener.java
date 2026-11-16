package fr.enimaloc.catapult.service.epic.connect;

/** Receives what Epic Connect pushes on an {@link EpicConnectClient} connection. */
public interface EpicConnectListener {

    /** The server confirmed the subscription's connection ({@code core.connect.v1.connected}). */
    void onConnected(String connectionId);

    /** Every other {@code MESSAGE} frame (presence updates and the like). */
    void onMessage(EpicConnectMessage message);

    /**
     * Called once when the connection ends; {@code cause} is null when {@link EpicConnectClient#close()} ended it,
     * an {@link EpicConnectException} otherwise.
     */
    void onClosed(Throwable cause);
}
