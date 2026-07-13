package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MinecraftFriendService;
import fr.enimaloc.catapult.service.MinecraftGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiMinecraftConnectControllerTest {

    @Mock private MinecraftFriendService friendService;
    @Mock private MinecraftGateService gateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private Jwt jwt;

    @InjectMocks private ApiMinecraftConnectController controller;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        UUID userId = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(gateService.isAvailableFor(user)).thenReturn(true);
    }

    private MinecraftFriendLink link(MinecraftFriendLink.Status status) {
        var account = new MinecraftServiceAccount();
        account.setMinecraftUsername("CatapultBot1");
        var l = new MinecraftFriendLink();
        l.setUser(user);
        l.setServiceAccount(account);
        l.setMinecraftName("jeb_");
        l.setStatus(status);
        return l;
    }

    @Test
    void get_noLink_returnsStatusNone() {
        when(friendService.getLink(user)).thenReturn(Optional.empty());

        var response = controller.status(jwt);

        assertThat(response.status()).isEqualTo("NONE");
    }

    @Test
    void get_pendingLink_exposesServiceAccountUsername() {
        when(friendService.getLink(user)).thenReturn(Optional.of(link(MinecraftFriendLink.Status.PENDING)));

        var response = controller.status(jwt);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.minecraftName()).isEqualTo("jeb_");
        assertThat(response.serviceAccountUsername()).isEqualTo("CatapultBot1");
    }

    @Test
    void post_enrolls_andReturnsLinkState() {
        when(friendService.enroll(user, "jeb_")).thenReturn(link(MinecraftFriendLink.Status.PENDING));

        var response = controller.enroll(jwt, Map.of("name", "jeb_"));

        assertThat(response.status()).isEqualTo("PENDING");
    }

    @Test
    void post_unknownPlayer_maps404() {
        when(friendService.enroll(user, "nexistepas")).thenThrow(
                new MinecraftFriendService.MinecraftEnrollmentException(
                        MinecraftFriendService.MinecraftEnrollmentException.Reason.UNKNOWN_PLAYER, "introuvable"));

        assertThatThrownBy(() -> controller.enroll(jwt, Map.of("name", "nexistepas")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void post_noCapacity_maps503() {
        when(friendService.enroll(user, "jeb_")).thenThrow(
                new MinecraftFriendService.MinecraftEnrollmentException(
                        MinecraftFriendService.MinecraftEnrollmentException.Reason.NO_CAPACITY, "plein"));

        assertThatThrownBy(() -> controller.enroll(jwt, Map.of("name", "jeb_")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void post_blankName_maps400() {
        assertThatThrownBy(() -> controller.enroll(jwt, Map.of("name", "   ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void post_noAccountAvailable_maps503() {
        when(friendService.enroll(user, "jeb_")).thenThrow(
                new MinecraftFriendService.MinecraftEnrollmentException(
                        MinecraftFriendService.MinecraftEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE, "aucun"));

        assertThatThrownBy(() -> controller.enroll(jwt, Map.of("name", "jeb_")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void delete_callsUnenroll() {
        controller.unenroll(jwt);

        org.mockito.Mockito.verify(friendService).unenroll(user);
    }

    @Test
    void get_gateClosed_returnsUnavailable() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        var response = controller.status(jwt);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(friendService);
    }

    @Test
    void post_gateClosed_maps403() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThatThrownBy(() -> controller.enroll(jwt, Map.of("name", "jeb_")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void sync_gateClosed_maps403() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThatThrownBy(() -> controller.syncNow(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void delete_gateClosed_maps403() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThatThrownBy(() -> controller.unenroll(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
