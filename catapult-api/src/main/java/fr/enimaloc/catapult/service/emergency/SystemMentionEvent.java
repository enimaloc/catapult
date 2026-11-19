package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;

/**
 * Published when a chat message of a channel starts by mentioning the system account. Published as an event, not
 * called, because the chat service sits under the services that answer it (bot toggle, chat sending).
 */
public record SystemMentionEvent(UserAccount channel, MentionSender sender, SystemMention mention) {
}
