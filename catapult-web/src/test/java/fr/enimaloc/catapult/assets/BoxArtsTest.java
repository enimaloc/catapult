package fr.enimaloc.catapult.assets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoxArtsTest {
    private final BoxArts boxArts = new BoxArts();

    @Test
    void aTwitchBoxArt_goesThroughTheProxy_atTheSizeItServes() {
        assertThat(boxArts.url("https://static-cdn.jtvnw.net/ttv-boxart/33214-52x72.jpg"))
                .isEqualTo("/assets/ext/boxart/33214-96x128.jpg");
        assertThat(boxArts.url("https://static-cdn.jtvnw.net/ttv-boxart/2_IGDB-{width}x{height}.jpg"))
                .isEqualTo("/assets/ext/boxart/2_IGDB-96x128.jpg");
        assertThat(boxArts.url("https://static-cdn.jtvnw.net/ttv-boxart/1-285x380.png"))
                .isEqualTo("/assets/ext/boxart/1-96x128.png");
    }

    @Test
    void anyOtherUrl_isNotShown() {
        assertThat(boxArts.url(null)).isNull();
        assertThat(boxArts.url("")).isNull();
        assertThat(boxArts.url("https://example.org/ttv-boxart/1-52x72.jpg")).isNull();
        assertThat(boxArts.url("https://static-cdn.jtvnw.net/ttv-boxart/../x-52x72.jpg")).isNull();
        assertThat(boxArts.url("https://static-cdn.jtvnw.net/ttv-boxart/./Just%20Chatting-52x72.jpg")).isNull();
    }
}
