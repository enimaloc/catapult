package fr.enimaloc.catapult.common.dto;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The stream title's fields: the shorter constructors leave them unset, and payloads from before them still read. */
class StreamTitleDtosTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void userSettings_withoutTheTitles_haveThemOffAndUnset() {
        UserSettingsDto bare = new UserSettingsDto(true, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false);
        assertThat(bare.titleFeatureEnabled()).isFalse();
        assertThat(bare.titleTemplate()).isNull();
        assertThat(bare.titleAvailable()).isFalse();
        assertThat(bare.noGameTitle()).isNull();
        assertThat(bare.incompleteFallbackTitle()).isNull();

        UserSettingsDto withoutFallbacks = new UserSettingsDto(true, Set.of(), null, null, Set.of(), false, false,
                false, null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false, List.of(), List.of(),
                true, "Playing {{GAME}}", true);
        assertThat(withoutFallbacks.titleTemplate()).isEqualTo("Playing {{GAME}}");
        assertThat(withoutFallbacks.noGameTitle()).isNull();
        assertThat(withoutFallbacks.incompleteFallbackTitle()).isNull();
    }

    @Test
    void bindings_withoutATitle_haveNone() {
        assertThat(new BindingDto("b1", "AUTO", "STEAM", "Doom", "7", "DOOM", false, true, Set.of(), true, false,
                Set.of(), null, List.of()).title()).isNull();
        assertThat(new CustomBindingDto(UUID.randomUUID(), "Art", "509660", "Art", List.of(), List.of(), false)
                .title()).isNull();
    }

    @Test
    void requests_withoutATitle_leaveItAsItIs() {
        assertThat(new CustomBindingRequest("Art", "509660", "Art", Set.of(), Set.of(), false).title()).isNull();
        assertThat(new NoGameSettingsRequest("1", "Just Chatting", Set.of(), true, true, true).title()).isNull();
        assertThat(new IncompleteFallbackRequest("1", "Art", Set.of()).title()).isNull();
    }

    @Test
    void titleEvent_withoutTheFallbacks_hasNoneOfThem() {
        assertThat(new ChannelEvents.Settings.Title(true, "x"))
                .isEqualTo(new ChannelEvents.Settings.Title(true, "x", null, null));
    }

    @Test
    void aSettingsPayloadFromBeforeTheTitle_stillReads_offAndNotOffered() {
        UserSettingsDto read = json.readValue("""
                {"cclFeatureEnabled":true,"blockedCcls":[],"noGameCcls":[],"applyDefaultOnStreamStart":false,
                 "applyDefaultOnNoGame":false,"applyDefaultOnStreamEnd":false,"incompleteFallbackCcls":[],
                 "availableCcls":[],"twFeatureEnabled":false,"blockedTws":[],"availableTws":[],
                 "raidRestoreGameAvailable":false}""", UserSettingsDto.class);

        assertThat(read.titleFeatureEnabled()).isNull();
        assertThat(read.titleAvailable()).isNull();
        assertThat(read.noGameTitle()).isNull();
    }
}
