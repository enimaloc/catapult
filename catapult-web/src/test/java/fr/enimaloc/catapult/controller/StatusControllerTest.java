package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StatusController.class)
@Import(WebSecurityConfig.class)
class StatusControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiClient apiClient;

    @Test
    void tellsWhetherCatapultApiAnswers_uncached() throws Exception {
        when(apiClient.getAnonymous("/api/health", Map.class)).thenReturn(new ApiClient.Result<>(200, Map.of("status", "UP")));

        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"available\":true}"));
    }

    @Test
    void unavailable_whenDownOrNotUp() {
        ApiClient client = mock(ApiClient.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        StatusController controller = new StatusController(client, clock);
        when(client.getAnonymous(eq("/api/health"), any())).thenReturn(
                new ApiClient.Result<>(502, null), new ApiClient.Result<>(200, Map.of("status", "DOWN")),
                new ApiClient.Result<>(200, Map.of("status", "UP")));

        assertThat(available(controller)).isFalse();
        clock.advance(StatusController.TTL);
        assertThat(available(controller)).isFalse();
        clock.advance(StatusController.TTL);
        assertThat(available(controller)).isTrue();
    }

    @Test
    void asksCatapultApiOnceEveryFewSeconds() {
        ApiClient client = mock(ApiClient.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        StatusController controller = new StatusController(client, clock);
        when(client.getAnonymous(eq("/api/health"), any())).thenReturn(new ApiClient.Result<>(200, Map.of("status", "UP")));

        available(controller);
        available(controller);
        verify(client, times(1)).getAnonymous(eq("/api/health"), any());
        clock.advance(StatusController.TTL);
        available(controller);
        verify(client, times(2)).getAnonymous(eq("/api/health"), any());
    }

    private static boolean available(StatusController controller) {
        ResponseEntity<Map<String, Boolean>> response = controller.status();
        return response.getBody().get("available");
    }
}
