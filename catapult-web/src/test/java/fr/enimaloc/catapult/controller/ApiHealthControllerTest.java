package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.relay.ApiRelayController;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiHealthProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiHealthController.class)
@Import(WebSecurityConfig.class)
class ApiHealthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiHealthProbe probe;

    @Test
    void answers200UpWhileTheApiAnswers() throws Exception {
        when(probe.up()).thenReturn(true);

        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }

    @Test
    void answers503DownOtherwise() throws Exception {
        when(probe.up()).thenReturn(false);

        mvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(content().string("{\"status\":\"DOWN\"}"));
    }

    @Test
    void isNotRelayedToTheApi() {
        AntPathMatcher matcher = new AntPathMatcher();

        assertThat(Arrays.stream(ApiRelayController.PATHS).noneMatch(p -> matcher.match(p, "/api/health"))).isTrue();
    }
}
