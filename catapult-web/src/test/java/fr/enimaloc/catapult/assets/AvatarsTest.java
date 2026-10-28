package fr.enimaloc.catapult.assets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarsTest {
    private final Avatars avatars = new Avatars();

    @Test
    void aTwitchPicture_goesThroughTheProxy_atTheSizeAsked() {
        assertThat(avatars.url("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-profile_image-300x300.png", 64))
                .isEqualTo("/assets/ext/avatar/64/jtv_user_pictures/abc-profile_image-300x300.png");
        assertThat(avatars.url("https://static-cdn.jtvnw.net/user-default-pictures-uv/d1-profile_image-300x300.png", 128))
                .isEqualTo("/assets/ext/avatar/128/user-default-pictures-uv/d1-profile_image-300x300.png");
    }

    @Test
    void anyOtherPicture_isLeftAsItIs() {
        assertThat(avatars.url("https://example.org/me.png", 64)).isEqualTo("https://example.org/me.png");
        assertThat(avatars.url("https://static-cdn.jtvnw.net/other/x.png", 64)).isEqualTo("https://static-cdn.jtvnw.net/other/x.png");
        assertThat(avatars.url("https://static-cdn.jtvnw.net/jtv_user_pictures/a.png", 50))
                .isEqualTo("https://static-cdn.jtvnw.net/jtv_user_pictures/a.png");
        assertThat(avatars.url(null, 64)).isNull();
    }
}
