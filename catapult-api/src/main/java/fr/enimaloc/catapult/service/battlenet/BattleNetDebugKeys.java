package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The keys naming a member in what a BGS session exchanges, for the debug recording: its Battle.net account id, its
 * BattleTag (the only name a sent invitation has) and its invitation id.
 */
public final class BattleNetDebugKeys {

    private BattleNetDebugKeys() {
    }

    public static Set<String> of(BattleNetFriendLink link) {
        Set<String> keys = new HashSet<>();
        if (link.getBnetAccountId() != 0) {
            keys.add(account(link.getBnetAccountId()));
        }
        if (link.getBattleTag() != null) {
            keys.add(tag(link.getBattleTag()));
        }
        if (link.getInvitationId() != null) {
            keys.add(invitation(link.getInvitationId()));
        }
        return keys;
    }

    public static Set<String> of(FriendEvent event) {
        return switch (event) {
            case FriendEvent.FriendAdded e -> Set.of(account(e.accountId()), tag(e.battleTag()));
            case FriendEvent.FriendRemoved e -> Set.of(account(e.accountId()), tag(e.battleTag()));
            case FriendEvent.InvitationReceived e -> Set.of(account(e.accountId()), tag(e.battleTag()));
            case FriendEvent.InvitationRemoved e -> Set.of(account(e.accountId()), tag(e.battleTag()));
            case FriendEvent.InvitationSent e -> Set.of(invitation(e.invitationId()), tag(e.targetName()));
            case FriendEvent.InvitationSentRemoved e -> Set.of(invitation(e.invitationId()), tag(e.targetName()));
        };
    }

    public static String account(long bnetAccountId) {
        return "id:" + bnetAccountId;
    }

    public static String tag(String battleTag) {
        return "tag:" + (battleTag == null ? "" : battleTag.toLowerCase(Locale.ROOT));
    }

    public static String invitation(long invitationId) {
        return "inv:" + invitationId;
    }
}
