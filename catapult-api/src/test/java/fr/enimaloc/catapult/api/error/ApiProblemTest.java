package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.enimaloc.catapult.common.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiProblemTest {

    @Test
    void carriesItsCodeStatusDetailAndProperties() {
        ApiProblem problem = new ApiProblem(
                ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED, "Microsoft refused", Map.of("upstream", "AADSTS70002"));

        assertThat(problem.code()).isEqualTo(ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED);
        assertThat(problem.getStatusCode().value()).isEqualTo(502);
        assertThat(problem.getReason()).isEqualTo("Microsoft refused");
        assertThat(problem.getBody().getDetail()).isEqualTo("Microsoft refused");
        assertThat(problem.getBody().getProperties())
                .containsEntry("code", "minecraft_device_code_refused")
                .containsEntry("upstream", "AADSTS70002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "title", "status", "detail", "instance", "code"})
    void rejectsAReservedPropertyName(String name) {
        assertThatThrownBy(() -> new ApiProblem(ErrorCode.CONFLICT, "Taken", Map.of(name, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }
}
