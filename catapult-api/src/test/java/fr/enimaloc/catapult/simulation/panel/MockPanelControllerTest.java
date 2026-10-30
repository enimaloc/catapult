package fr.enimaloc.catapult.simulation.panel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** The panel's page and assets: served from the classpath to a loopback client only. */
class MockPanelControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new MockPanelController()).build();
    }

    @Test
    void panel_is_served_on_direct_loopback() throws Exception {
        String page = mvc.perform(get("/mock"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", "no-store"))
                // Avatars come from Twitch's CDN or any URL typed in; scripts and styles stay the page's own
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; img-src 'self' https:"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Mock control panel").contains("/mock/panel.js").contains("/mock/panel.css");
    }

    @Test
    void assets_are_served_with_their_types() throws Exception {
        mvc.perform(get("/mock/panel.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
        mvc.perform(get("/mock/panel.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void panel_is_404_with_forwarded_for() throws Exception {
        mvc.perform(get("/mock").header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/mock/panel.js").header("X-Forwarded-For", "127.0.0.1, 203.0.113.7"))
                .andExpect(status().isNotFound());
    }

    /** What the RemoteIpValve leaves of a relayed remote client: its own address. */
    @Test
    void panel_is_404_for_a_remote_client() throws Exception {
        mvc.perform(get("/mock").with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isNotFound());
    }

    /** A page of another site whose name was rebound to 127.0.0.1 still names its own host. */
    @Test
    void panel_is_404_under_a_foreign_host_name() throws Exception {
        mvc.perform(get("/mock").header("Host", "attacker.example"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/mock").header("Host", "127.0.0.1:8081"))
                .andExpect(status().isOk());
    }
}
