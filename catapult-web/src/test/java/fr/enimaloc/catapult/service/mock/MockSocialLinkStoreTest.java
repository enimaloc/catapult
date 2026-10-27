package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockSocialLinkStoreTest {
    private final MockSocialLinkStore store = new MockSocialLinkStore();

    private static SocialLinkRequest request(String url) {
        return new SocialLinkRequest(SocialPlatform.X, " ", url, true);
    }

    @Test
    void startsWithTheGithubLink() {
        assertThat(store.list()).singleElement().satisfies(l -> {
            assertThat(l.platform()).isEqualTo(SocialPlatform.GITHUB);
            assertThat(l.position()).isZero();
        });
    }

    @Test
    void createRefusesWhatCatapultApiRefuses() {
        assertThat(store.create(request("http://x.example"))).isEqualTo(400);
        assertThat(store.create(request("javascript:alert(1)"))).isEqualTo(400);
        assertThat(store.create(request("https://x.example/" + "a".repeat(2048)))).isEqualTo(400);
        assertThat(store.create(request(" https://x.example"))).isEqualTo(400);
        assertThat(store.create(new SocialLinkRequest(SocialPlatform.X, "l".repeat(65), "https://x.example", true)))
                .isEqualTo(400);
        assertThat(store.create(null)).isEqualTo(400);
        assertThat(store.create(new SocialLinkRequest(null, null, "https://x.example", true))).isEqualTo(400);
        assertThat(store.list()).hasSize(1);
    }

    @Test
    void createStoresABlankLabelAsNull_atTheEnd() {
        assertThat(store.create(request("https://x.example"))).isEqualTo(201);
        assertThat(store.list().get(1)).satisfies(l -> {
            assertThat(l.label()).isNull();
            assertThat(l.position()).isEqualTo(1);
        });
    }

    @Test
    void updateAndDelete() {
        long id = store.list().get(0).id();
        assertThat(store.update(99, request("https://x.example"))).isEqualTo(404);
        assertThat(store.update(id, request("http://x.example"))).isEqualTo(400);
        assertThat(store.update(id, new SocialLinkRequest(SocialPlatform.BLUESKY, "B", "https://b.example", false)))
                .isEqualTo(200);
        assertThat(store.list().get(0)).isEqualTo(
                new SocialLinkDto(id, SocialPlatform.BLUESKY, "B", "https://b.example", false, 0));
        assertThat(store.delete(99)).isEqualTo(404);
        assertThat(store.delete(id)).isEqualTo(204);
        assertThat(store.list()).isEmpty();
    }

    @Test
    void orderNeedsExactlyTheIds_andRenumbers() {
        store.create(request("https://x.example"));
        List<SocialLinkDto> before = store.list();
        long a = before.get(0).id();
        long b = before.get(1).id();
        assertThat(store.order(null)).isEqualTo(400);
        assertThat(store.order(List.of(a))).isEqualTo(400);
        assertThat(store.order(List.of(a, a))).isEqualTo(400);
        assertThat(store.order(List.of(a, 99L))).isEqualTo(400);
        assertThat(store.order(Arrays.asList(a, null))).isEqualTo(400);
        assertThat(store.order(List.of(b, a))).isEqualTo(204);
        assertThat(store.list()).extracting(SocialLinkDto::id).containsExactly(b, a);
        assertThat(store.list()).extracting(SocialLinkDto::position).containsExactly(0, 1);
    }
}
