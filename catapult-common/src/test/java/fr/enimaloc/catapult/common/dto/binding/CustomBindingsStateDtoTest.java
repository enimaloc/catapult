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

    @Test
    void theActiveBindingIsFoundByItsId_andNeverWritten() {
        var art = new CustomBindingDto(java.util.UUID.randomUUID(), "Art", "509660", "Art", List.of(), List.of(), false);
        var state = new CustomBindingsStateDto(true, List.of(art), art.id(), null);

        assertThat(state.active()).isEqualTo(art);
        assertThat(new CustomBindingsStateDto(true, List.of(art), null, null).active()).isNull();
        assertThat(new CustomBindingsStateDto(true, List.of(art), java.util.UUID.randomUUID(), null).active()).isNull();
        assertThat(json.writeValueAsString(state)).doesNotContain("\"active\"");
    }
}
