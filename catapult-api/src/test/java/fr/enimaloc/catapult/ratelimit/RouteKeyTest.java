package fr.enimaloc.catapult.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RouteKeyTest {

    @Test
    void theCallersTemplate_isTheRoute() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://Store.SteamPowered.com/app/620?l=french"));
        request.getAttributes().put(RouteKey.URI_TEMPLATE_ATTRIBUTE, "https://store.steampowered.com/app/{id}?l={l}");

        RouteKey key = RouteKey.of(request);

        assertThat(key).isEqualTo(new RouteKey("store.steampowered.com", "GET", "/app/{id}"));
        assertThat(key.id()).isEqualTo("GET store.steampowered.com/app/{id}");
    }

    @Test
    void aConcatenatedTemplate_isNormalizedToo() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://displaycatalog.mp.microsoft.com/v7.0/products/9NBLGGH4R315ABCD"));
        request.getAttributes().put(RouteKey.URI_TEMPLATE_ATTRIBUTE,
                "https://displaycatalog.mp.microsoft.com/v7.0/products/9NBLGGH4R315ABCD?market=FR");

        assertThat(RouteKey.of(request).path()).isEqualTo("/v7.0/products/{id}");
    }

    @Test
    void withoutATemplate_identifiersBecomeId() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
                URI.create("https://displaycatalog.mp.microsoft.com/v7.0/products/9NBLGGH4R315ABCD?market=FR"));

        assertThat(RouteKey.of(request).path()).isEqualTo("/v7.0/products/{id}");
        assertThat(RouteKey.normalizedPath("/users/xuid(123)/titles/42/")).isEqualTo("/users/{id}/titles/{id}/");
        assertThat(RouteKey.normalizedPath("")).isEqualTo("/");
        assertThat(RouteKey.normalizedPath(null)).isEqualTo("/");
    }

    @Test
    void aTemplatesPath_dropsSchemeHostAndQuery() {
        assertThat(RouteKey.templatePath("/api/appdetails?appids={id}")).isEqualTo("/api/appdetails");
        assertThat(RouteKey.templatePath("https://host.example")).isEqualTo("/");
        assertThat(RouteKey.templatePath("?x={x}")).isEqualTo("/");
    }

    @Test
    void thePriority_isLiveUnlessSetAndComesBackAfter() {
        AtomicReference<RateLimitPriority> inner = new AtomicReference<>();
        AtomicReference<Boolean> innerCancelled = new AtomicReference<>();

        assertThat(RateLimitPriority.current()).isEqualTo(RateLimitPriority.LIVE);
        assertThat(RateLimitPriority.cancelled()).isFalse();
        RateLimitPriority.run(RateLimitPriority.BULK, () -> true, () -> {
            String nested = RateLimitPriority.call(RateLimitPriority.LIVE, () -> false, () -> {
                inner.set(RateLimitPriority.current());
                return "ok";
            });
            assertThat(nested).isEqualTo("ok");
            innerCancelled.set(RateLimitPriority.cancelled());
            assertThat(RateLimitPriority.current()).isEqualTo(RateLimitPriority.BULK);
        });

        assertThat(inner.get()).isEqualTo(RateLimitPriority.LIVE);
        assertThat(innerCancelled.get()).isTrue();
        assertThat(RateLimitPriority.current()).isEqualTo(RateLimitPriority.LIVE);
    }
}
