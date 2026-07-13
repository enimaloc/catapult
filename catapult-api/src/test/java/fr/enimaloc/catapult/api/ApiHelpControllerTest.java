package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MinecraftGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiHelpControllerTest {

    @Mock private MessageSource messageSource;
    @Mock private MinecraftGateService gateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private Jwt jwt;

    @InjectMocks private ApiHelpController controller;

    private final UserAccount user = new UserAccount();
    private final Locale locale = Locale.FRENCH;

    @BeforeEach
    void setup() {
        UUID userId = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(messageSource.getMessage(eq("help.connections.title"), any(), any(), eq(locale)))
                .thenReturn("Connexions");
        when(messageSource.getMessage(eq("help.connections.body"), any(), any(), eq(locale)))
                .thenReturn("<p>corps</p>");
        when(messageSource.getMessage(eq("help.connections.body.minecraft"), any(), any(), eq(locale)))
                .thenReturn("<p>minecraft</p>");
    }

    @Test
    void connections_gateOpen_appendsMinecraftSection() {
        when(gateService.isAvailableFor(user)).thenReturn(true);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p><p>minecraft</p>");
    }

    @Test
    void connections_gateClosed_omitsMinecraftSection() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }

    @Test
    void connections_anonymous_omitsMinecraftSection() {
        var content = controller.help("connections", locale, null);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }

    @Test
    void otherCard_neverTouchesGate() {
        when(messageSource.getMessage(eq("help.status.title"), any(), any(), eq(locale))).thenReturn("État");
        when(messageSource.getMessage(eq("help.status.body"), any(), any(), eq(locale))).thenReturn("<p>status</p>");

        var content = controller.help("status", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>status</p>");
        org.mockito.Mockito.verifyNoInteractions(gateService);
    }

    @Test
    void connections_nonUuidSubject_omitsMinecraftSection() {
        when(jwt.getSubject()).thenReturn("service-account-client");

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }

    @Test
    void connections_unknownUser_omitsMinecraftSection() {
        when(userAccountRepository.findById(any())).thenReturn(Optional.empty());

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }
}
