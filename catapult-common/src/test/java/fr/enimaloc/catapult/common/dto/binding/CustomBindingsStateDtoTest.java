package fr.enimaloc.catapult.common.dto.binding;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomBindingsStateDtoTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void anAbsentLimit_isLeftOutOfTheJson() {
        String body = json.writeValueAsString(new CustomBindingsStateDto(true, List.of(), null, null));

        assertThat(body).doesNotContain("limit");
    }

    @Test
    void aLimit_isWritten() {
        String body = json.writeValueAsString(new CustomBindingsStateDto(true, List.of(), null, null, 5));

        assertThat(body).contains("\"limit\":5");
    }
}
