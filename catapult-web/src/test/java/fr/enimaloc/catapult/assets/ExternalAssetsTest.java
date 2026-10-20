package fr.enimaloc.catapult.assets;

import fr.enimaloc.catapult.MutableClock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAssetsTest {
    static final String COMMIT = "25c0c7be44d5692e323e4a5779202bdb31f1e2e8";
    static final String FONT = "s/materialicons/v145/flUhRq6tzZclQEJ-Vdg-IuiaDsNc.woff2";

    MockWebServer server;
    MutableClock clock;
    ExternalAssets assets;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        base = server.url("/").toString();
        clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        assets = new ExternalAssets(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), clock,
                base + "icon?family=Material+Icons", base + "gstatic/", base + "twitchat/");
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    static String text(ExternalAssets.Result result) {
        return new String(result.body(), StandardCharsets.UTF_8);
    }

    @Test
    void materialIcons_pointsItsFontsAtTheProxy_askingAsABrowserWould() throws Exception {
        server.enqueue(new MockResponse().setBody("@font-face { src: url(" + base + "gstatic/" + FONT + ") format('woff2'); }"));

        ExternalAssets.Result css = assets.materialIconsCss();

        assertThat(css.status()).isEqualTo(200);
        assertThat(text(css)).isEqualTo("@font-face { src: url(/assets/ext/fonts/gstatic/" + FONT + ") format('woff2'); }");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/icon?family=Material+Icons");
        assertThat(request.getHeader("User-Agent")).contains("Chrome/");
    }

    @Test
    void materialIcons_isRefused_whenItPointsAnywhereElse() {
        server.enqueue(new MockResponse().setBody("@font-face { src: url('https://tracker.example/x.woff2'); }"));
        assertThat(assets.materialIconsCss().status()).isEqualTo(502);
        server.enqueue(new MockResponse().setBody("@import url(" + base + "gstatic/../evil.css);"));
        assertThat(assets.materialIconsCss().status()).isEqualTo(502);
    }

    @Test
    void font_isFetchedOnce_thenKept() throws Exception {
        server.enqueue(new MockResponse().setBody("wOF2"));

        assertThat(text(assets.font(FONT))).isEqualTo("wOF2");
        assertThat(text(assets.font(FONT))).isEqualTo("wOF2");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).isEqualTo("/gstatic/" + FONT);
    }

    @Test
    void font_onlyOfGooglesFontPaths() {
        assertThat(assets.font("s/materialicons/v1/../../x.woff2").status()).isEqualTo(404);
        assertThat(assets.font("css2?family=x").status()).isEqualTo(404);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void twitchatIcon_comesFromItsCommitsIconFolder() throws Exception {
        server.enqueue(new MockResponse().setBody("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"));

        assertThat(text(assets.twitchatIcon(COMMIT, "add"))).startsWith("<svg");
        assertThat(server.takeRequest().getPath()).isEqualTo("/twitchat/" + COMMIT + "/src_front/assets/icons/add.svg");
    }

    @Test
    void twitchatIcon_mustBeAnSvg() {
        server.enqueue(new MockResponse().setBody("<html>not an icon</html>"));
        assertThat(assets.twitchatIcon(COMMIT, "add").status()).isEqualTo(502);
    }

    @Test
    void anAnswerOverItsSizeLimit_isRefused() {
        server.enqueue(new MockResponse().setBody("x".repeat(ExternalAssets.Kind.CSS.maxBytes + 1)));
        assertThat(assets.materialIconsCss().status()).isEqualTo(502);
        // Announced too big: refused without reading it.
        server.enqueue(new MockResponse().setBody("<svg/>").setHeader("Content-Length", ExternalAssets.Kind.SVG.maxBytes + 1));
        assertThat(assets.twitchatIcon(COMMIT, "big").status()).isEqualTo(502);
    }

    @Test
    void redirectsAreNotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/x.svg"));
        assertThat(assets.twitchatIcon(COMMIT, "add").status()).isEqualTo(502);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aMissingFile_isA404_keptAWhile_aFailureIsNotKept() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(assets.twitchatIcon(COMMIT, "gone").status()).isEqualTo(404);
        assertThat(assets.twitchatIcon(COMMIT, "gone").status()).isEqualTo(404);
        assertThat(server.getRequestCount()).isEqualTo(1);
        clock.advance(ExternalAssets.MISSING_TTL);
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(assets.twitchatIcon(COMMIT, "gone").status()).isEqualTo(502);
        server.enqueue(new MockResponse().setBody("<svg/>"));
        assertThat(assets.twitchatIcon(COMMIT, "gone").status()).isEqualTo(200);
    }

    @Test
    void anUnreachableUpstream_isA502() throws IOException {
        server.shutdown();
        assertThat(assets.materialIconsCss().status()).isEqualTo(502);
    }

    @Test
    void keptFilesExpire() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("<svg/>"));
        server.enqueue(new MockResponse().setBody("<svg id=\"new\"/>"));
        assets.twitchatIcon(COMMIT, "add");
        clock.advance(ExternalAssets.Kind.SVG.ttl.plus(Duration.ofSeconds(1)));

        assertThat(text(assets.twitchatIcon(COMMIT, "add"))).contains("new");
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
    }
}
