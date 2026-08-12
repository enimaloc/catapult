package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(200, Map.of("id", "p1")));
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "{\"message\":\"Live.\"}", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNull();
        verify(apiClient).postForResult(eq("/api/channels/{username}/twitchat/presets"), any(), eq("streamer"));
    }

    @Test
    void createTwitchatPreset_noJsSubmit_returnsRedirect() {
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(200, Map.of("id", "p1")));

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "{\"message\":\"Live.\"}", null, model);

        assertThat(view).isEqualTo("redirect:/channels/streamer");
    }

    @Test
    void createTwitchatPreset_apiFailure_setsErrorAttribute() {
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(400, Map.of("message", "message ne doit pas être vide")));
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "not json", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNotNull();
        assertThat(model.getAttribute("twitchatPresetError")).asString()
                .contains("message ne doit pas être vide");
    }

    @Test
    void createTwitchatPreset_apiFailure_noJsSubmit_doesNotLeakErrorIntoModel() {
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(400, Map.of("message", "message ne doit pas être vide")));

        Model model = new ExtendedModelMap();
        String view = newController().createTwitchatPreset("streamer", "STREAM_STARTED", "Discret",
                "not json", null, model);

        assertThat(view).isEqualTo("redirect:/channels/streamer");
        assertThat(model.getAttribute("twitchatPresetError")).isNull();
    }

    @Test
    void updateTwitchatPreset_success_setsNoErrorAttribute() {
        when(apiClient.putForResult(eq("/api/channels/{username}/twitchat/presets/{id}"), any(),
                eq("streamer"), eq("p1")))
                .thenReturn(new ApiClient.ApiResult(200, Map.of()));
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().updateTwitchatPreset("streamer", "p1", "Discret",
                "{\"message\":\"Live.\"}", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNull();
    }

    @Test
    void updateTwitchatPreset_apiFailure_setsErrorAttribute() {
        when(apiClient.putForResult(eq("/api/channels/{username}/twitchat/presets/{id}"), any(),
                eq("streamer"), eq("p1")))
                .thenReturn(new ApiClient.ApiResult(400, Map.of("message", "JSON invalide")));
        when(apiClient.get(eq("/api/channels/{username}/twitchat/presets"),
                any(ParameterizedTypeReference.class), eq("streamer"))).thenReturn(List.of());
        when(apiClient.get(eq("/api/channels/{username}/twitchat/active-presets"), eq(Map.class), eq("streamer")))
                .thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = newController().updateTwitchatPreset("streamer", "p1", "Discret",
                "not json", "true", model);

        assertThat(view).isEqualTo("fragments/twitchat-presets :: twitchat-presets-body");
        assertThat(model.getAttribute("twitchatPresetError")).isNotNull();
        assertThat(model.getAttribute("twitchatPresetError")).asString().contains("JSON invalide");
    }

    @Test
    void testTwitchatPreset_success_returnsNoContent() {
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets/test"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(200, Map.of()));

        ResponseEntity<Void> response = newController().testTwitchatPreset("streamer", "STREAM_STARTED",
                "{\"message\":\"Live.\"}", "true");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void testTwitchatPreset_apiFailure_returnsErrorStatusNotAck() {
        when(apiClient.postForResult(eq("/api/channels/{username}/twitchat/presets/test"), any(), eq("streamer")))
                .thenReturn(new ApiClient.ApiResult(400, Map.of("message", "JSON invalide")));

        ResponseEntity<Void> response = newController().testTwitchatPreset("streamer", "STREAM_STARTED",
                "not json", "true");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
