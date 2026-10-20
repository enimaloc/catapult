package fr.enimaloc.catapult.assets;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExternalAssetController.class)
@Import(WebSecurityConfig.class)
class ExternalAssetControllerTest {
    static final String COMMIT = "25c0c7be44d5692e323e4a5779202bdb31f1e2e8";

    @Autowired MockMvc mvc;
    @MockitoBean ExternalAssets assets;

    static ExternalAssets.Result ok(String body) {
        return new ExternalAssets.Result(200, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void servesTheStyleSheet_asCss() throws Exception {
        when(assets.materialIconsCss()).thenReturn(ok(".material-icons{}"));

        mvc.perform(get("/assets/ext/fonts/material-icons.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(content().string(".material-icons{}"));
    }

    @Test
    void servesAFont_withItsOwnType_forAYear() throws Exception {
        when(assets.font("s/materialicons/v145/flUhRq6tzZclQEJ-Vdg-IuiaDsNc.woff2")).thenReturn(ok("wOF2"));

        mvc.perform(get("/assets/ext/fonts/gstatic/s/materialicons/v145/flUhRq6tzZclQEJ-Vdg-IuiaDsNc.woff2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "font/woff2"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public"));
    }

    @Test
    void servesATwitchatIcon_asASandboxedSvg() throws Exception {
        when(assets.twitchatIcon(COMMIT, "add")).thenReturn(ok("<svg/>"));

        mvc.perform(get("/assets/ext/twitchat/" + COMMIT + "/add.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", ExternalAssetController.SVG_CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void relaysAMissingOrUnavailableFile_uncached() throws Exception {
        when(assets.twitchatIcon(COMMIT, "gone")).thenReturn(ExternalAssets.Result.MISSING);
        when(assets.materialIconsCss()).thenReturn(ExternalAssets.Result.FAILED);

        mvc.perform(get("/assets/ext/twitchat/" + COMMIT + "/gone.svg"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/assets/ext/fonts/material-icons.css")).andExpect(status().isBadGateway());
    }

    @Test
    void anythingElse_isNeverFetched() throws Exception {
        for (String path : new String[]{
                "/assets/ext/twitchat/main/add.svg",
                "/assets/ext/twitchat/" + COMMIT + "/add.png",
                "/assets/ext/twitchat/" + COMMIT + "/a.b.svg",
                "/assets/ext/fonts/gstatic/s/materialicons/v145/x.css",
                "/assets/ext/fonts/gstatic/css2",
                "/assets/ext/fonts/other.css"}) {
            mvc.perform(get(path)).andExpect(status().is4xxClientError());
        }
        verify(assets, never()).twitchatIcon(anyString(), anyString());
        verify(assets, never()).font(anyString());
        verifyNoInteractions(assets);
    }
}
