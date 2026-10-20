package fr.enimaloc.catapult.common.dto.channel;

/**
 * An enabled Twitchat widget's OBS connection, for the widget page's first load (the live updates
 * come as {@link fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig}, same fields).
 * {@code twitchatBranch} is the Twitchat API to speak, see {@link TwitchatData}.
 */
public record ConfigResponse(String obsHost, Integer obsPort, String obsPassword, String twitchatBranch) {}
