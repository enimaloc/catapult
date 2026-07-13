package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;

/**
 * Interface d'une commande chat Twitch.
 * L'ajout d'une nouvelle commande ne nécessite aucune modification de TwitchChatListener.
 */
public interface ChatCommand {

    String getName();

    ChatCommandEvent.SenderRole getRequiredPermission();

    /**
     * Réservée à l'owner de l'application ({@code app.owner-id}), quel que soit
     * le channel. Vérifié par le CommandRegistry via le Twitch ID du sender.
     */
    default boolean isOwnerOnly() {
        return false;
    }

    Object execute(UserAccount user, List<String> args);
}
