package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelActionsControllerTest {

    @Mock ApiClient apiClient;

    ChannelActionsController newController() {
        return new ChannelActionsController(apiClient);
    }

    @Test
    void twitchatPresetsFragment_populatesModelFromApi() {
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer")))
                .thenReturn(List.of(Map.of("id", "p1", "eventType", "STREAM_STARTED", "name", "Discret",
                        "payloadJson", "{\"message\":\"Live.\"}")));
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of("STREAM_STARTED", "p1"));

        Model model = new ExtendedModelMap();
        String view = newController().twitchatPresetsFragment("streamer", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("channelUsername")).isEqualTo("streamer");
        assertThat(model.getAttribute("twitchatActivePresets")).isEqualTo(Map.of("STREAM_STARTED", "p1"));
    }

    @Test
    void twitchatPresetsFragment_noJsSubmit_returnsRedirect() {
        Model model = new ExtendedModelMap();
        String view = newController().twitchatPresetsFragment("streamer", null, model);

        assertThat(view).isEqualTo("redirect:/channels/streamer");
    }

    @Test
    void createTwitchatPreset_postsThenReturnsFragment() {
        when(apiClient.post(eq("/api/channels/{username}/twitchat/presets"), any(), eq(Object.class), eq("streamer")))
                .thenReturn(new Object());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "{\"message\":\"Live.\"}", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNull();
        verify(apiClient).post(eq("/api/channels/{username}/twitchat/presets"), any(), eq(Object.class), eq("streamer"));
    }

    @Test
    void createTwitchatPreset_noJsSubmit_returnsRedirect() {
        when(apiClient.post(eq("/api/channels/{username}/twitchat/presets"), any(), eq(Object.class), eq("streamer")))
                .thenReturn(new Object());

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "{\"message\":\"Live.\"}", null, model);

        assertThat(view).isEqualTo("redirect:/channels/streamer");
    }

    @Test
    void createTwitchatPreset_apiFailure_setsErrorAttribute() {
        when(apiClient.post(eq("/api/channels/{username}/twitchat/presets"), any(), eq(Object.class), eq("streamer")))
                .thenReturn(null);
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "not json", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNotNull();
    }
}
