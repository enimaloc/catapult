package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserApiCommandController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
// /api/user/** is permitAll (widget-token authenticated, no JWT) in ApiSecurityConfig.
@AutoConfigureMockMvc(addFilters = false)
class UserApiCommandControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean WidgetTokenService widgetTokenService;
    @MockitoBean DynamicCommandResolver dynamicCommandResolver;

    private final UUID token = UUID.randomUUID();
    private final UserAccount user = new UserAccount();

    @Test
    void answersTheCommand_asPlainText_withItsArguments() throws Exception {
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(dynamicCommandResolver.renderReadOnly(user, "!hello", List.of("a", "b")))
                .thenReturn(Optional.of("Salut a b"));

        mvc.perform(get("/api/user/command/{uuid}/hello", token).queryParam("args", "  a  b "))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("Salut a b"));
    }

    @Test
    void anUnknownToken_is404() throws Exception {
        when(widgetTokenService.resolve(token)).thenReturn(Optional.empty());

        mvc.perform(get("/api/user/command/{uuid}/game", token)).andExpect(status().isNotFound());
    }

    /** A bot posts the body as is: nothing to say is an empty answer, not an error message. */
    @Test
    void noSuchCommand_answersNothing() throws Exception {
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(dynamicCommandResolver.renderReadOnly(any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/user/command/{uuid}/game", token))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void aNameNoCommandCouldHave_isNotLookedUp() throws Exception {
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/user/command/{uuid}/Game!", token))
                .andExpect(status().isOk()).andExpect(content().string(""));
        verify(dynamicCommandResolver, never()).renderReadOnly(any(), any(), any());
    }

    @Test
    void cutsTheAnswerToTheBotsLimit() throws Exception {
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(dynamicCommandResolver.renderReadOnly(user, "!game", List.of()))
                .thenReturn(Optional.of("x ".repeat(400)));

        String body = mvc.perform(get("/api/user/command/{uuid}/game", token).queryParam("max", "100"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).hasSizeLessThanOrEqualTo(100).endsWith("x…");
    }

    @Test
    void cut_keepsWholeWords_andFallsBackToAHardCutOnALongWord() {
        assertThat(UserApiCommandController.cut("short", 20)).isEqualTo("short");
        assertThat(UserApiCommandController.cut("hello wonderful world", 18)).isEqualTo("hello wonderful…");
        assertThat(UserApiCommandController.cut("a " + "z".repeat(40), 20)).isEqualTo("a " + "z".repeat(17) + "…");
    }
}
