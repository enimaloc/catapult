package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwitchatEventActionsBootstrapTest {

    @Mock TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @Mock TwitchatEventActionService eventActionService;
    @InjectMocks TwitchatEventActionsBootstrap bootstrap;

    @Test
    void initializesEveryWaitingAccount_evenAfterOneFails() {
        UserAccount first = new UserAccount();
        UserAccount second = new UserAccount();
        when(widgetSettingsRepository.findUsersAwaitingEventActions()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("boom")).when(eventActionService).ensureInitialized(first);

        bootstrap.run();

        verify(eventActionService).ensureInitialized(second);
    }
}
