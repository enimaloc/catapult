package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Published when a valid command is recognised in the Twitch chat. The chat command system is
 * retired: this event has no consumer, and nothing answers it in chat.
 */
@Getter
public class ChatCommandEvent extends ApplicationEvent {

    /** Ordered least to most privileged — {@link CommandRegistry} relies on {@code ordinal()}
     *  for its hierarchy check (a higher tier always satisfies a lower requirement). */
    public enum SenderRole {
        VIEWERS, FOLLOWERS, SUBS, VIP, MODERATOR, BROADCASTER
    }

    private final transient UserAccount user;
    private final String command;
    private final List<String> args;
    private final SenderRole senderRole;
    /** Chatter's Twitch ID (nullable: rewards, legacy transports). */
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
