package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TwitchatEventStreamController.class)
@Import(WebSecurityConfig.class)
class TwitchatEventStreamControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean TwitchatDispatchHub hub;

    @Test
    void loggedOut_noContent() throws Exception {
        when(apiService.currentUserId()).thenReturn(null);
        mvc.perform(get("/me/twitchat/events")).andExpect(status().isNoContent());
    }

    @Test
    void loggedIn_opensTheOwnersStream() throws Exception {
        UUID owner = UUID.randomUUID();
        when(apiService.currentUserId()).thenReturn(owner);
        when(hub.subscribe(owner)).thenReturn(new SseEmitter(0L));

        mvc.perform(get("/me/twitchat/events")).andExpect(request().asyncStarted());
        verify(hub).subscribe(owner);
    }
}
