package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.title.TitleHelpSections;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiHelpControllerTest {

    @Mock private MessageSource messageSource;
    @Mock private MinecraftGateService gateService;
    @Mock private TitleHelpSections titleHelpSections;
    @Mock private SystemTwitchAccountService systemAccount;
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
        when(messageSource.getMessage(eq("help.dtdd.title"), any(), any(), eq(locale))).thenReturn("État");
        when(messageSource.getMessage(eq("help.dtdd.body"), any(), any(), eq(locale))).thenReturn("<p>dtdd</p>");

        var content = controller.help("dtdd", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>dtdd</p>");
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
        when(titleHelpSections.forAccount(userId)).thenReturn(new TitleHelpSections.Offered(true, true));

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p><p>conditions</p><p>drops</p>");
    }

    @Test
    void titleSettings_conditionsWithoutDrops_appendsConditionsOnly() {
        when(titleHelpSections.forAccount(userId)).thenReturn(new TitleHelpSections.Offered(true, false));

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p><p>conditions</p>");
    }

    @Test
    void titleSettings_noneOffered_omitsBoth() {
        when(titleHelpSections.forAccount(userId)).thenReturn(TitleHelpSections.Offered.NONE);

        var content = controller.help("title-settings", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>titre</p>");
    }

    @Test
    void titleSettings_anonymous_omitsBoth() {
        var content = controller.help("title-settings", locale, null);

        assertThat(content.body()).isEqualTo("<p>titre</p>");
        org.mockito.Mockito.verifyNoInteractions(titleHelpSections);
    }

    @Test
    void help_status_appendsChatCommandsWithSystemLogin() {
        when(messageSource.getMessage(eq("help.status.body"), any(), any(), eq(locale))).thenReturn("<p>status</p>");
        when(messageSource.getMessage(eq("help.status.body.chat"), any(), any(), eq(locale))).thenReturn("<h3>chat</h3>");
        when(systemAccount.getSystemTwitchUsername()).thenReturn("catapult_bot");

        var content = controller.help("status", locale, jwt);

        assertThat(content.body()).isEqualTo("<p>status</p><h3>chat</h3>");
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("help.status.body.chat"), args.capture(), any(), eq(locale));
        assertThat(args.getValue()).hasSize(4);
        assertThat(args.getValue()[3]).isEqualTo("catapult_bot");
    }

    @Test
    void help_status_noSystemAccount_usesFallback() {
        when(messageSource.getMessage(eq("help.status.body.chat"), any(), any(), eq(locale))).thenReturn("<h3>chat</h3>");
        when(messageSource.getMessage(eq("help.status.bot_fallback"), any(), any(), eq(locale))).thenReturn("le bot");
        when(systemAccount.getSystemTwitchUsername()).thenReturn(null);

        controller.help("status", locale, jwt);

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("help.status.body.chat"), args.capture(), any(), eq(locale));
        assertThat(args.getValue()[3]).isEqualTo("le bot");
    }

    @Test
    void help_otherCard_hasNoChatSection() {
        var content = controller.help("connections", locale, jwt);

        assertThat(content.body()).doesNotContain("chat");
        org.mockito.Mockito.verifyNoInteractions(systemAccount);
    }
}
