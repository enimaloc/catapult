package fr.enimaloc.catapult.common.dto.twitchat;

public record TwitchatAction(String label, String actionType, String url, String message, String theme) {

    public static TwitchatAction urlButton(String label, String url, String theme) {
        return new TwitchatAction(label, "url", url, null, theme);
    }
}
