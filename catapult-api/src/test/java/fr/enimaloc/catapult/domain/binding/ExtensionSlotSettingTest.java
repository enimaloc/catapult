package fr.enimaloc.catapult.domain.binding;

import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionSlotSettingTest {

    @Test
    void validated_rejects_mobile() {
        assertThatThrownBy(() -> ExtensionSlotSetting.validated(List.of(new ExtensionSlotDto(Type.MOBILE, 1, "a", "A"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validated_rejects_panel_4_and_component_3_and_overlay_2() {
        for (ExtensionSlotDto dto : List.of(
            new ExtensionSlotDto(Type.PANEL, 4, "a", "A"),
            new ExtensionSlotDto(Type.COMPONENT, 3, "a", "A"),
            new ExtensionSlotDto(Type.OVERLAY, 2, "a", "A"),
            new ExtensionSlotDto(Type.PANEL, 0, "a", "A"))) {
            assertThatThrownBy(() -> ExtensionSlotSetting.validated(List.of(dto)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validated_rejects_duplicate_slot() {
        assertThatThrownBy(() -> ExtensionSlotSetting.validated(List.of(
            new ExtensionSlotDto(Type.PANEL, 1, "a", "A"),
            new ExtensionSlotDto(Type.PANEL, 1, "b", "B"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validated_keeps_none_as_null_id() {
        Set<ExtensionSlotSetting> result = ExtensionSlotSetting.validated(List.of(new ExtensionSlotDto(Type.PANEL, 1, null, null)));
        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getExtensionId()).isNull();
    }

    @Test
    void dto_round_trip() {
        ExtensionSlotDto dto = new ExtensionSlotDto(Type.COMPONENT, 2, "ext", "Ext");
        assertThat(ExtensionSlotSetting.fromDto(dto).toDto()).isEqualTo(dto);
    }
}
