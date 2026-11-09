package fr.enimaloc.catapult.service.twitch;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Slot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TwitchExtensionJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void extension_reads_helix_list_entry() {
        TwitchExtension extension = mapper.readValue("""
                {"can_activate": true, "id": "abc", "name": "Ext", "type": ["panel", "overlay"], "version": "1.0.0"}
                """, TwitchExtension.class);

        assertThat(extension.canActivate()).isTrue();
        assertThat(extension.types()).containsExactly(TwitchExtension.Type.PANEL, TwitchExtension.Type.OVERLAY);
    }

    @Test
    void placement_reads_numbered_slots() {
        TwitchExtensionPlacement placement = mapper.readValue("""
                {"panel": {"1": {"active": true, "id": "p1", "name": "Panel", "version": "1.0"}, "2": {"active": false}},
                 "overlay": {"1": {"active": false}},
                 "component": {"1": {"active": true, "id": "c1", "version": "2.0", "x": 10, "y": 20}}}
                """, TwitchExtensionPlacement.class);

        assertThat(placement.panel().first()).isEqualTo(new Slot(true, "p1", "Panel", "1.0", null, null));
        assertThat(placement.panel().second()).isEqualTo(Slot.empty());
        assertThat(placement.component().first()).isEqualTo(new Slot(true, "c1", null, "2.0", 10, 20));
    }

    @Test
    void partial_update_writes_only_the_slots_it_is_given() {
        TwitchExtensionPlacement update = TwitchExtensionPlacement.update()
                .panel(2, Slot.of("p2", "1.0"))
                .component(1, Slot.empty())
                .build();

        JsonNode json = mapper.valueToTree(new TwitchServiceImpl.WrappedResponse<>(update));

        assertThat(json.at("/data/panel/2/id").asString()).isEqualTo("p2");
        assertThat(json.at("/data/panel").has("1")).isFalse();
        assertThat(json.at("/data/panel").has("3")).isFalse();
        assertThat(json.at("/data/component/1/active").asBoolean()).isFalse();
        assertThat(json.at("/data/component").has("2")).isFalse();
        assertThat(json.at("/data").has("overlay")).isFalse();
    }

    @Test
    void placement_writes_numbered_slots_without_nulls_or_derived_properties() {
        TwitchExtensionPlacement placement = new TwitchExtensionPlacement(
                new TwitchExtensionPlacement.ComponentPlacement(Slot.at("c1", "2.0", 10, 20), Slot.empty()),
                TwitchExtensionPlacement.OverlayPlacement.empty(),
                new TwitchExtensionPlacement.PanelPlacement(Slot.of("p1", "1.0"), Slot.empty(), Slot.empty()));

        JsonNode json = mapper.valueToTree(new TwitchServiceImpl.WrappedResponse<>(placement));

        assertThat(json.at("/data/panel/1/id").asString()).isEqualTo("p1");
        assertThat(json.at("/data/panel/1").has("x")).isFalse();
        assertThat(json.at("/data/panel/2/active").asBoolean()).isFalse();
        assertThat(json.at("/data/component/1/x").asInt()).isEqualTo(10);
        assertThat(json.at("/data/panel").has("slots")).isFalse();
        assertThat(json.at("/data/panel").has("first")).isFalse();
    }
}
