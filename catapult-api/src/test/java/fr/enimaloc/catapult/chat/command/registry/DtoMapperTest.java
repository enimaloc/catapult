package fr.enimaloc.catapult.chat.command.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMapperTest {

    private record Sample(String title, String category) {}

    @Test
    void toMapConvertsRecordComponentsInDeclarationOrder() {
        Map<String, Object> map = DtoMapper.toMap(new Sample("Valorant", "FPS"));
        assertThat(map).containsExactly(Map.entry("title", "Valorant"), Map.entry("category", "FPS"));
    }

    @Test
    void keysReturnsRecordComponentNamesInDeclarationOrder() {
        assertThat(DtoMapper.keys(Sample.class)).containsExactly("title", "category");
    }
}
