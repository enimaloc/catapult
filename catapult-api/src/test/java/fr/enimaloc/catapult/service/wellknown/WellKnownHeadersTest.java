package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WellKnownHeadersTest {
    static WellKnownHeader h(String name, String value) {
        return new WellKnownHeader(name, value);
    }

    static void refused(int status, List<WellKnownHeader> headers) {
        assertThatThrownBy(() -> WellKnownHeaders.validate(status, headers))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void acceptsAPlainFileAndARedirect() {
        assertThatCode(() -> WellKnownHeaders.validate(200, List.of(h("Cache-Control", "max-age=60")))).doesNotThrowAnyException();
        assertThatCode(() -> WellKnownHeaders.validate(302, List.of(h("Location", "https://example.org/account/password")))).doesNotThrowAnyException();
        assertThatCode(() -> WellKnownHeaders.validate(301, List.of(h("Location", "http://a")))).doesNotThrowAnyException();
        assertThatCode(() -> WellKnownHeaders.validate(308, List.of(h("location", "/settings/password")))).doesNotThrowAnyException();
        assertThatCode(() -> WellKnownHeaders.validate(200, List.of(h("Content-Security-Policy", "default-src 'self'")))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 199, 600, 99})
    void refusesAStatusOutOf200To599(int status) {
        refused(status, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Length", "transfer-encoding", "CONNECTION", "Content-Type", "Set-Cookie", "x-content-type-options"})
    void refusesReservedNames(String name) {
        refused(200, List.of(h(name, "x")));
    }

    @Test
    void refusesBadNamesValuesDuplicatesAndTooMany() {
        refused(200, List.of(h("Bad Name", "x")));
        refused(200, List.of(h("", "x")));
        refused(200, List.of(h("X-A", "a\r\nSet-Cookie: x")));
        refused(200, List.of(h("X-A", "a\u0000")));
        refused(200, List.of(h("X-A", "x".repeat(1025))));
        refused(200, List.of(h("X-A", null)));
        refused(200, List.of(h("X-A", "1"), h("x-a", "2")));
        List<WellKnownHeader> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add(h("X-" + i, "v"));
        refused(200, many);
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example", "javascript:alert(1)", "ftp://x", "relative/path", "", "https://", "http:// x"})
    void refusesABadLocationOnARedirect(String location) {
        refused(302, List.of(h("Location", location)));
    }

    @Test
    void aRedirectNeedsALocation() {
        refused(301, List.of());
        assertThatCode(() -> WellKnownHeaders.validate(304, List.of())).doesNotThrowAnyException();
    }

    @Test
    void jsonRoundTrip() {
        List<WellKnownHeader> headers = List.of(h("Location", "/x"), h("Cache-Control", "no-store"));
        assertThat(WellKnownHeaders.fromJson(WellKnownHeaders.toJson(headers))).isEqualTo(headers);
        assertThat(WellKnownHeaders.fromJson("[]")).isEmpty();
        assertThat(WellKnownHeaders.fromJson(null)).isEmpty();
    }
}
