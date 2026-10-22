package fr.enimaloc.catapult.common.dto.notification;

import fr.enimaloc.catapult.common.event.ChannelEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON shapes catapult-web sends and reads for the notification delivery channels. */
class NotificationChannelsDtoTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void channels_areTheirEnumNames() {
        NotificationChannelsDto dto = json.readValue("{\"channels\":[\"TWITCHAT\",\"STREAM_START_CHAT\"]}",
                NotificationChannelsDto.class);

        assertThat(dto.channels()).containsExactlyInAnyOrder(NotificationChannel.TWITCHAT, NotificationChannel.STREAM_START_CHAT);
        assertThat(json.writeValueAsString(new NotificationChannelsDto(Set.of(NotificationChannel.TWITCHAT))))
                .isEqualTo("{\"channels\":[\"TWITCHAT\"]}");
    }

    @Test
    void noChannels_readsAsAnEmptySet() {
        assertThat(json.readValue("{}", NotificationChannelsDto.class).channels()).isEmpty();
        assertThat(json.readValue("{\"channels\":null}", NotificationChannelsDto.class).channels()).isEmpty();
    }

    @Test
    void flushResult_isTheDeliveredCount() {
        assertThat(json.writeValueAsString(new NotificationFlushResultDto(3))).isEqualTo("{\"delivered\":3}");
    }

    @Test
    void deliveryPending_carriesTheUserIds() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String written = json.writeValueAsString(new ChannelEvents.NotificationDeliveryPending(List.of(id)));

        assertThat(written).isEqualTo("{\"userIds\":[\"00000000-0000-0000-0000-000000000001\"]}");
        assertThat(json.readValue(written, ChannelEvents.NotificationDeliveryPending.class).userIds()).containsExactly(id);
    }
}
