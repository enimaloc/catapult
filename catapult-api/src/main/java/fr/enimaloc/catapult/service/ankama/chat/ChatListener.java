package fr.enimaloc.catapult.service.ankama.chat;

/** Receives what the chat server pushes on an {@link AnkamaChatClient} connection. */
public interface ChatListener {

    void onEvent(ChatMessages.Event event);

    /** Called once when the connection ends; {@code cause} is null when {@link AnkamaChatClient#close()} ended it. */
    void onClosed(Throwable cause);
}
