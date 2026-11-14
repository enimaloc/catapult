package fr.enimaloc.catapult.service.ankama.chat;

import java.io.IOException;

/** The chat server refused the chat token during the handshake. Never carries the token. */
public class AnkamaChatAuthException extends IOException {

    public AnkamaChatAuthException(String message) {
        super(message);
    }
}
