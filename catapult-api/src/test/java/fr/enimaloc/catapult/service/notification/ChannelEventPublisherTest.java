package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.service.binding.BindingDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChannelEventPublisherTest {

    @Test
    void bindingUpserted_publishesFullDto() {
        RedisEventPublisher redis = mock(RedisEventPublisher.class);
        ChannelEventPublisher publisher = new ChannelEventPublisher(redis);
        UUID ownerId = UUID.randomUUID();
        BindingDto dto = new BindingDto(
                UUID.randomUUID().toString(),
                "STEAM",
                "Skyrim",
                "Skyrim",
                Set.of("ccl1"),
                "AUTO",
                true,
                false
        );

        publisher.bindingUpserted(ownerId, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publishChannel(eq(ownerId), eq("binding.upserted"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("binding");
        assertThat(payload.get("binding")).isEqualTo(dto);
    }
}
