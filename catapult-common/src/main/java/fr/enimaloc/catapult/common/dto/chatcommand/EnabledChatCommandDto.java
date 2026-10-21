package fr.enimaloc.catapult.common.dto.chatcommand;

/**
 * One of a streamer's enabled chat commands, as the page moving them to another chat bot lists it.
 *
 * @param name       the command, "!" included
 * @param permission who may run it in chat (a SenderRole name: VIEWERS ... BROADCASTER)
 * @param acts       whether it changes something (the game, a ban, a message...), which another
 *                   bot fetching its answer can't have it do
 * @param takesArgs  whether it reads what follows it in chat, which the other bot must then pass on
 */
public record EnabledChatCommandDto(String name, String permission, boolean acts, boolean takesArgs) {}
