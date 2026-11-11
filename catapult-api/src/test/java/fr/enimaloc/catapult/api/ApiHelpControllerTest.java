package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.drops.DropsGate;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
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
    @Mock private StreamTitleService streamTitleService;
    @Mock private DropsGate dropsGate;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private Jwt jwt;

    @InjectMocks private ApiHelpController controller;

    private final UUID userId = UUID.randomUUID();
    private final Locale locale = Locale.FRENCH;

    @BeforeEach
    void setup() {
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(gateService.isAvailableForAccount(userId)).thenReturn(false);
        when(messageSource.getMessage(eq("help.connections.title"), any(), any(), eq(locale)))
                .thenReturn("Connexions");
        when(messageSource.getMessage(eq("help.connections.body"), any(), any(), eq(locale)))
                .thenReturn("<p>corps</p>");
        when(messageSource.getMessage(eq("help.connections.body.minecraft"), any(), any(), eq(locale)))
                .thenReturn("<p>minecraft</p>");
        when(messageSource.getMessage(eq("help.connections.body.xbox"), any(), any(), eq(locale)))
                .thenReturn("<p>xbox</p>");
        when(messageSource.getMessage(eq("help.title-settings.body"), any(), any(), eq(locale)))
                .thenReturn("<p>titre</p>");
        when(messageSource.getMessage(eq("help.title-settings.body.conditions"), any(), any(), eq(locale)))
                .thenReturn("<p>conditions</p>");
        when(messageSource.getMessage(eq("help.title-settings.body.drops"), any(), any(), eq(locale)))
                .thenReturn("<p>drops</p>");
    }

    @Test
    void connections_gateOpen_appendsMinecraftSection() {
        when(gateService.isAvailableForAccount(userId)).thenReturn(true);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p><p>minecraft</p>");
    }

    @Test
    void connections_gateClosed_omitsMinecraftSection() {
        when(gateService.isAvailableForAccount(userId)).thenReturn(false);

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
        when(gateService.isAvailableForAccount(any())).thenReturn(false);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }

    @Test
    void connections_xboxEnabled_appendsXboxSection() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "xboxEnabled", true);
        when(gateService.isAvailableForAccount(userId)).thenReturn(false);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p><p>xbox</p>");
    }

    @Test
    void connections_xboxDisabled_omitsXboxSection() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "xboxEnabled", false);
        when(gateService.isAvailableForAccount(userId)).thenReturn(false);

        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>corps</p>");
    }

    @Test
    void titleSettings_conditionsAndDrops_appendsBothSections() {
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(streamTitleService.conditionsAvailableReadOnly(user)).thenReturn(true);
        when(dropsGate.isAvailableReadOnly(user)).thenReturn(true);

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p><p>conditions</p><p>drops</p>");
    }

    @Test
    void titleSettings_conditionsWithoutDrops_appendsConditionsOnly() {
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(streamTitleService.conditionsAvailableReadOnly(user)).thenReturn(true);
        when(dropsGate.isAvailableReadOnly(user)).thenReturn(false);

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p><p>conditions</p>");
    }

    @Test
    void titleSettings_dropsWithoutConditions_omitsBoth() {
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(streamTitleService.conditionsAvailableReadOnly(user)).thenReturn(false);
        when(dropsGate.isAvailableReadOnly(user)).thenReturn(true);

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p>");
    }

    @Test
    void titleSettings_anonymous_omitsBoth() {
        var content = controller.help("title-settings", locale, null);

        assertThat(content.body()).isEqualTo("<p>titre</p>");
    }
}
