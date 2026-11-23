package fr.enimaloc.catapult.event.chess;

import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's chess accounts as they now stand: the Chess.com and the Lichess names they declared (null for none)
 * and the links to their profile pages. Names only.
 */
public record ChessAccountChangedEvent(String username, String chesscomUsername, String chesscomProfileUrl,
                                       String lichessUsername, String lichessProfileUrl)
        implements ChannelUpdatedEvent {

    public static ChessAccountChangedEvent of(String username, ChessData data) {
        return new ChessAccountChangedEvent(username, data.chesscomUsername(), data.chesscomProfileUrl(),
                data.lichessUsername(), data.lichessProfileUrl());
    }
}
