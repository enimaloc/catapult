package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.UserAccount;
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

    private final transient UserAccount user;
    private final String command;
    private final List<String> args;
    private final SenderRole senderRole;
    /** Twitch ID du chatteur (nullable : rewards, transports legacy). */
    private final String senderTwitchId;

    public ChatCommandEvent(Object source, UserAccount user, String command,
                            List<String> args, SenderRole senderRole) {
        this(source, user, command, args, senderRole, null);
    }

    public ChatCommandEvent(Object source, UserAccount user, String command,
                            List<String> args, SenderRole senderRole, String senderTwitchId) {
        super(source);
        this.user = user;
        this.command = command;
        this.args = args;
        this.senderRole = senderRole;
        this.senderTwitchId = senderTwitchId;
    }
}
