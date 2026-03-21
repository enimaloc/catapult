package fr.esportline.catapult.chat;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Publié lorsqu'une commande valide est reconnue dans le chat Twitch.
 */
@Getter
public class ChatCommandEvent extends ApplicationEvent {

    public enum SenderRole {
        BROADCASTER, MODERATOR, EVERYONE
    }

    private final UserAccount user;
    private final String command;
    private final List<String> args;
    private final SenderRole senderRole;

    public ChatCommandEvent(Object source, UserAccount user, String command,
                            List<String> args, SenderRole senderRole) {
        super(source);
        this.user = user;
        this.command = command;
        this.args = args;
        this.senderRole = senderRole;
    }
}
