package fr.enimaloc.catapult.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void codesAreUniqueSnakeCase() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code))
                .allMatch(c -> c.matches("^[a-z]+(_[a-z]+)*$"))
                .doesNotHaveDuplicates();
    }

    @Test
    void customBindingCodesKeepTheirValues() {
        assertThat(ErrorCode.TWITCH_UNAVAILABLE.code()).isEqualTo("twitch_unavailable");
        assertThat(ErrorCode.TWITCH_UNAVAILABLE.status()).isEqualTo(409);
        assertThat(ErrorCode.BOT_DISABLED.code()).isEqualTo("bot_disabled");
        assertThat(ErrorCode.BOT_DISABLED.status()).isEqualTo(409);
        assertThat(ErrorCode.TWITCH_FAILED.code()).isEqualTo("twitch_failed");
        assertThat(ErrorCode.TWITCH_FAILED.status()).isEqualTo(502);
        assertThat(ErrorCode.NAME_TAKEN.code()).isEqualTo("name_taken");
        assertThat(ErrorCode.NAME_TAKEN.status()).isEqualTo(409);
        assertThat(ErrorCode.LIMIT_REACHED.code()).isEqualTo("limit_reached");
        assertThat(ErrorCode.LIMIT_REACHED.status()).isEqualTo(409);
        assertThat(ErrorCode.INVALID.code()).isEqualTo("invalid");
        assertThat(ErrorCode.INVALID.status()).isEqualTo(400);
    }

    @Test
    void propertyIsCode() {
        assertThat(ErrorCode.PROPERTY).isEqualTo("code");
    }

    @Test
    void fromCodeRoundTrips() {
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(ErrorCode.fromCode(c.code())).isEqualTo(Optional.of(c));
        }
        assertThat(ErrorCode.fromCode(null)).isEmpty();
        assertThat(ErrorCode.fromCode("nope")).isEmpty();
    }

    @Test
    void forStatusIsTheGenericCode() {
        assertThat(ErrorCode.forStatus(400)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.forStatus(401)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.forStatus(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.forStatus(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forStatus(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forStatus(406)).isEqualTo(ErrorCode.NOT_ACCEPTABLE);
        assertThat(ErrorCode.forStatus(409)).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.forStatus(410)).isEqualTo(ErrorCode.GONE);
        assertThat(ErrorCode.forStatus(413)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.forStatus(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.forStatus(429)).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.forStatus(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(502)).isEqualTo(ErrorCode.BAD_GATEWAY);
        assertThat(ErrorCode.forStatus(503)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.forStatus(418)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.forStatus(504)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
