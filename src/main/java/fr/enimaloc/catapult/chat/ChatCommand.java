package fr.esportline.catapult.chat;

import fr.esportline.catapult.domain.UserAccount;

import java.util.List;

/**
 * Interface d'une commande chat Twitch.
 * L'ajout d'une nouvelle commande ne nécessite aucune modification de TwitchChatListener.
 */
public interface ChatCommand {

    String getName();

    ChatCommandEvent.SenderRole getRequiredPermission();

    void execute(UserAccount user, List<String> args);
}
