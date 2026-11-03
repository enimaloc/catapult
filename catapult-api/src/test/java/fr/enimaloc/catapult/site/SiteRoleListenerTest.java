package fr.enimaloc.catapult.site;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteRoleListenerTest {

    @Mock UserAccountRepository users;
    @Mock TwitchEventSubService eventSub;
    @Mock TwitchChatService chat;
    @Mock GameStateService gameState;
    @InjectMocks SiteRoleListener listener;

    private final UserAccount a = new UserAccount();
    private final UserAccount b = new UserAccount();

    @Test
    void standby_disconnectsEveryBotUser_andClearsGameState() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(a, b));

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        for (UserAccount user : List.of(a, b)) {
            verify(eventSub).disconnect(user);
            verify(chat).disconnect(user);
            verify(gameState).clearState(user);
        }
        verify(eventSub, never()).init();
    }

    @Test
    void active_reconnectsEventSubAndChat() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(a, b));

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));

        verify(eventSub).init();
        verify(chat).connect(a);
        verify(chat).connect(b);
        verify(chat, never()).disconnect(a);
    }

    @Test
    void standby_oneUserFailing_stillDisconnectsTheOthers() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("boom")).when(eventSub).disconnect(a);
        doThrow(new IllegalStateException("boom")).when(gameState).clearState(a);

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        verify(chat).disconnect(a);
        verify(eventSub).disconnect(b);
        verify(chat).disconnect(b);
        verify(gameState).clearState(b);
    }

    @Test
    void active_eventSubInitFailing_stillReconnectsChat() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("boom")).when(eventSub).init();
        doThrow(new IllegalStateException("boom")).when(chat).connect(a);

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));

        verify(chat).connect(a);
        verify(chat).connect(b);
    }

    @Test
    void active_userLookupFailing_stillInitsEventSub() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenThrow(new IllegalStateException("db down"));

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, true));

        verify(eventSub).init();
    }

    @Test
    void standby_userLookupFailing_stillClosesEveryOpenSocket() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenThrow(new IllegalStateException("db down"));

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        verify(eventSub).disconnectAll("standby");
        verify(chat).disconnectAll("standby");
    }

    @Test
    void standby_disconnectAllFailing_stillDisconnectsEachUser() {
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(a));
        doThrow(new IllegalStateException("boom")).when(eventSub).disconnectAll("standby");

        listener.onSiteRoleChanged(new SiteRoleChangedEvent(this, false));

        verify(chat).disconnectAll("standby");
        verify(eventSub).disconnect(a);
        verify(chat).disconnect(a);
        verify(gameState).clearState(a);
    }
}
