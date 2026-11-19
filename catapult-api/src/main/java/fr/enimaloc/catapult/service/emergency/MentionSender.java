package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.chat.ChatCommandEvent;

/** Who wrote a chat message that mentions the system account; the role is the badge-derived one. */
public record MentionSender(String twitchId, String login, ChatCommandEvent.SenderRole role) {
}
