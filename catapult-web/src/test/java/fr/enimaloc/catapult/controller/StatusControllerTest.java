package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
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
    @MockitoBean ApiService apiService;

    @Test
    void tellsWhetherCatapultApiAnswers_uncached() throws Exception {
        when(apiService.apiAvailable()).thenReturn(true);

        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"available\":true}"));
    }

    @Test
    void unavailable_whileCatapultApiIsnt() {
        ApiService client = mock(ApiService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        StatusController controller = new StatusController(client, clock);
        when(client.apiAvailable()).thenReturn(false, false, true);

        assertThat(available(controller)).isFalse();
        clock.advance(StatusController.TTL);
        assertThat(available(controller)).isFalse();
        clock.advance(StatusController.TTL);
        assertThat(available(controller)).isTrue();
    }

    @Test
    void asksCatapultApiOnceEveryFewSeconds() {
        ApiService client = mock(ApiService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        StatusController controller = new StatusController(client, clock);
        when(client.apiAvailable()).thenReturn(true);

        available(controller);
        available(controller);
        verify(client, times(1)).apiAvailable();
        clock.advance(StatusController.TTL);
        available(controller);
        verify(client, times(2)).apiAvailable();
    }

    private static boolean available(StatusController controller) {
        ResponseEntity<Map<String, Boolean>> response = controller.status();
        return response.getBody().get("available");
    }
}
