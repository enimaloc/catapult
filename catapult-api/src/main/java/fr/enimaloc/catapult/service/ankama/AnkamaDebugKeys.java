package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The keys naming a member in what a chat session exchanges, for the debug recording: its Ankama user id once
 * known, and its name#tag (all a pending invite has).
 */
public final class AnkamaDebugKeys {

    private AnkamaDebugKeys() {
    }

    public static Set<String> of(AnkamaFriendLink link) {
        Set<String> keys = new HashSet<>();
        if (link.getAnkamaUserId() != null) {
            keys.add(user(link.getAnkamaUserId()));
        }
        if (link.getRequestedName() != null) {
            keys.add(nameTag(link.getRequestedName(), link.getRequestedTag()));
        }
        return keys;
    }

    public static Set<String> of(ChatMessages.Event event) {
        return switch (event) {
            case ChatMessages.InviteCreated e -> of(e.invite());
            case ChatMessages.InviteDeleted e -> of(e.invite());
            case ChatMessages.FriendCreated e -> of(e.friend());
            case ChatMessages.FriendDeleted e -> Set.of(user(e.friendUserId()));
            case ChatMessages.PresenceUpdated e -> Set.of(user(e.userId()));
            case ChatMessages.EndpointsUpdated e -> Set.of(user(e.userId()));
            case ChatMessages.Unknown ignored -> Set.of();
        };
    }

    private static Set<String> of(ChatMessages.Invite invite) {
        Set<String> keys = new HashSet<>(of(invite.inviter()));
        keys.addAll(of(invite.recipient()));
        return keys;
    }

    private static Set<String> of(ChatMessages.User user) {
        return user == null ? Set.of() : Set.of(user(user.userId()), nameTag(user.name(), user.tag()));
    }

    public static String user(long ankamaUserId) {
        return "id:" + ankamaUserId;
    }

    public static String nameTag(String name, String tag) {
        return ("tag:" + name + "#" + (tag == null ? "" : tag)).toLowerCase(Locale.ROOT);
    }
}
