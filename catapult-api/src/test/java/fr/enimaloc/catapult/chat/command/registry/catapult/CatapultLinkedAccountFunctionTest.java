package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultLinkedAccountFunctionTest {

    @Test
    void invokeReturnsSteamIdWhenLinked() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123456");

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("linkedAccount");
        assertThat(fn.invoke(user, new Object[]{"steam"})).isEqualTo("123456");
    }

    @Test
    void invokeReturnsEmptyStringWhenSteamNotLinked() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.invoke(user, new Object[]{"steam"})).isEqualTo("");
    }

    @Test
    void invokeReturnsMinecraftNameOnlyWhenLinkIsAccepted() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();
        MinecraftFriendLink link = new MinecraftFriendLink();
        link.setMinecraftName("Steve");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(minecraftRepo.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.invoke(user, new Object[]{"minecraft"})).isEqualTo("Steve");
    }

    @Test
    void invokeReturnsEmptyStringWhenMinecraftLinkIsStillPending() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();
        MinecraftFriendLink link = new MinecraftFriendLink();
        link.setMinecraftName("Steve");
        link.setStatus(MinecraftFriendLink.Status.PENDING);
        when(minecraftRepo.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.invoke(user, new Object[]{"minecraft"})).isEqualTo("");
    }

    @Test
    void invokeReturnsNonEmptyForXboxWhenTokenPresent() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();
        when(oAuthRepo.findByUserAndProvider(user, OAuthToken.Provider.XBOX))
            .thenReturn(Optional.of(new OAuthToken()));

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.invoke(user, new Object[]{"xbox"})).isNotEqualTo("");
    }

    @Test
    void invokeReturnsEmptyStringForUnrecognizedProvider() throws Exception {
        MinecraftFriendLinkRepository minecraftRepo = mock(MinecraftFriendLinkRepository.class);
        OAuthTokenRepository oAuthRepo = mock(OAuthTokenRepository.class);
        UserAccount user = new UserAccount();

        CatapultLinkedAccountFunction fn = new CatapultLinkedAccountFunction(minecraftRepo, oAuthRepo);
        assertThat(fn.invoke(user, new Object[]{"battlenet"})).isEqualTo("");
    }
}
