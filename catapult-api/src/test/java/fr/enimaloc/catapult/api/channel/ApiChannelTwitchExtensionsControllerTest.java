package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.Authz;
import fr.enimaloc.catapult.security.MethodSecurityConfig;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.ComponentPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.OverlayPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.PanelPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Slot;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChannelTwitchExtensionsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiUserResolver.class, MethodSecurityConfig.class, Authz.class})
class ApiChannelTwitchExtensionsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean TwitchService twitchService;

    private UserAccount owner;
    private UserAccount moderator;

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        return account;
    }

    private static RequestPostProcessor as(UserAccount account) {
        return jwt().jwt(j -> j.subject(account.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private String extensions() {
        return "/api/internal/users/" + owner.getId() + "/twitch/extensions";
    }

    @BeforeEach
    void setUp() {
        owner = account("streamer");
        moderator = account("mod");
        for (UserAccount account : List.of(owner, moderator)) {
            when(userAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        }
        when(channelAccessService.canAccess(any(UserAccount.class), any(UserAccount.class))).thenReturn(true);
    }

    @Test
    void lists_the_installed_extensions_then_the_active_slots() throws Exception {
        when(twitchService.getUserExtensions(owner)).thenReturn(List.of(
                new TwitchExtension(true, "p1", "Panel", List.of(TwitchExtension.Type.PANEL), "1.0"),
                new TwitchExtension(false, "c1", "Comp",
                        List.of(TwitchExtension.Type.COMPONENT, TwitchExtension.Type.MOBILE), "2.0")));
        when(twitchService.getActiveExtensions(owner)).thenReturn(new TwitchExtensionPlacement(
                new ComponentPlacement(Slot.empty(), new Slot(true, "c1", "Comp", "2.0", 10, 20)),
                OverlayPlacement.empty(),
                new PanelPlacement(new Slot(true, "p1", "Panel", "1.0", null, null), Slot.empty(), null)));

        mvc.perform(get(extensions()).with(as(moderator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(2))
                .andExpect(jsonPath("$.installed[0].id").value("p1"))
                .andExpect(jsonPath("$.installed[0].canActivate").value(true))
                .andExpect(jsonPath("$.installed[1].types[1]").value("MOBILE"))
                .andExpect(jsonPath("$.active.length()").value(2))
                .andExpect(jsonPath("$.active[0].type").value("COMPONENT"))
                .andExpect(jsonPath("$.active[0].slot").value(2))
                .andExpect(jsonPath("$.active[0].x").value(10))
                .andExpect(jsonPath("$.active[1].type").value("PANEL"))
                .andExpect(jsonPath("$.active[1].slot").value(1))
                .andExpect(jsonPath("$.active[1].name").value("Panel"));
    }

    @Test
    void answers_empty_lists_when_twitch_cannot_be_read() throws Exception {
        when(twitchService.getUserExtensions(owner)).thenReturn(List.of());
        when(twitchService.getActiveExtensions(owner)).thenReturn(TwitchExtensionPlacement.empty());

        mvc.perform(get(extensions()).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").isEmpty())
                .andExpect(jsonPath("$.active").isEmpty());
    }

    @Test
    void refuses_who_cannot_access_the_channel() throws Exception {
        when(channelAccessService.canAccess(any(UserAccount.class), any(UserAccount.class))).thenReturn(false);

        mvc.perform(get(extensions()).with(as(moderator))).andExpect(status().isForbidden());
        verifyNoInteractions(twitchService);
    }
}
