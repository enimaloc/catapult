package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.account.UserAccount;

import java.util.List;

/**
 * A Twitch chat command.
 * Adding a new command needs no change to TwitchChatListener.
 */
public interface ChatCommand {

    String getName();

    ChatCommandEvent.SenderRole getRequiredPermission();

    /**
     * Reserved to the application owner ({@code app.owner-id}), whatever the channel.
     * Checked by the CommandRegistry against the sender's Twitch ID.
     */
    default boolean isOwnerOnly() {
        return false;
    }

    Object execute(UserAccount user, List<String> args);
}
