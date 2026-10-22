package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryDispatcher;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.STREAM_START_CHAT;
import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiMeNotificationChannelsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
class ApiMeNotificationChannelsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationDeliveryService deliveryService;
    @MockitoBean NotificationDeliveryDispatcher deliveryDispatcher;
    @MockitoBean UserAccountRepository userRepo;
    @MockitoBean ChannelAccessService channelAccessService;

    UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void get_returnsTheMembersChannels() throws Exception {
        when(deliveryService.channels(user.getId())).thenReturn(Set.of(TWITCHAT));

        mvc.perform(get("/api/me/notification-channels").with(jwt().jwt(j -> j.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"channels\":[\"TWITCHAT\"]}"));
    }

    @Test
    void put_replacesThem() throws Exception {
        mvc.perform(put("/api/me/notification-channels").with(jwt().jwt(j -> j.subject(user.getId().toString()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":[\"TWITCHAT\",\"STREAM_START_CHAT\"]}"))
                .andExpect(status().isNoContent());

        verify(deliveryService).replaceChannels(user, Set.of(TWITCHAT, STREAM_START_CHAT));
    }

    @Test
    void put_withoutChannels_clearsThem() throws Exception {
        mvc.perform(put("/api/me/notification-channels").with(jwt().jwt(j -> j.subject(user.getId().toString()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        verify(deliveryService).replaceChannels(user, Set.of());
    }

    @Test
    void flush_sendsTheOwnersPendingTwitchatNotifications() throws Exception {
        when(deliveryDispatcher.flushTwitchat(user)).thenReturn(2);

        mvc.perform(post("/api/me/twitchat/notifications/flush").with(jwt().jwt(j -> j.subject(user.getId().toString()))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"delivered\":2}"));
    }

    @Test
    void unknownCaller_isUnauthorized() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(userRepo.findById(unknown)).thenReturn(Optional.empty());

        mvc.perform(post("/api/me/twitchat/notifications/flush").with(jwt().jwt(j -> j.subject(unknown.toString()))).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
