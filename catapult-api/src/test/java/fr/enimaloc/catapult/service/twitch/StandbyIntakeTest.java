package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.site.SiteRoles;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StandbyIntakeTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final RestClient restClient = mock(RestClient.class);

    private TwitchEventSubService eventSub() {
        return new TwitchEventSubService(tokens, users, null, null, null, restClient, null, null, null, null, null,
            SiteRoles.standby(), null, null);
    }

    @Test
    void init_connectsNobodyOnStandby() {
        eventSub().init();

        verifyNoInteractions(users, tokens, restClient);
    }

    @Test
    void connect_doesNothingOnStandby() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        eventSub().connect(user);

        verifyNoInteractions(users, tokens, restClient);
    }

    @Test
    void ircConnect_doesNothingOnStandby() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        new IrcTwitchChatService(tokens, users, null, null, null, null, null, SiteRoles.standby()).connect(user);

        verifyNoInteractions(users, tokens);
    }

    @Test
    void eventSubChatConnect_doesNothingOnStandby() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        new EventSubTwitchChatService(tokens, users, null, null, null, restClient, null, null, null, null, null,
            null, null, SiteRoles.standby()).connect(user);

        verifyNoInteractions(users, tokens, restClient);
    }
}
