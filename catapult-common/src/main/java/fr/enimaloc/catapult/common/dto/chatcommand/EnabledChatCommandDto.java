package fr.enimaloc.catapult.common.dto.chatcommand;

/**
 * One of a streamer's enabled chat commands, as the page moving them to another chat bot lists it.
 *
 * @param name       the command, "!" included
 * @param permission who may run it in chat (a SenderRole name: VIEWERS ... BROADCASTER)
 * @param presetKey  the preset it was made from ("game", "store"...), which says what the user API
 *                   gives in its place; null for a command of the streamer's own
 */
public record EnabledChatCommandDto(String name, String permission, String presetKey) {}
