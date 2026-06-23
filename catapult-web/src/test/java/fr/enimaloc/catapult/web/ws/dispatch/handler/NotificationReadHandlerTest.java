package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationReadHandlerTest {

    @Test
    void posts_to_api_with_notification_id() throws Exception {
        ApiClient api = mock(ApiClient.class);
        var handler = new NotificationReadHandler(api);
        UUID id = UUID.randomUUID();

        Object out = handler.handle((WsSession) null, Map.of("notificationId", id.toString()));

        assertThat(out).isNull();
        verify(api).post(eq("/api/notifications/{id}/read"), isNull(), eq(id));
    }

    @Test
    void missing_id_rejected() {
        ApiClient api = mock(ApiClient.class);
        var handler = new NotificationReadHandler(api);
        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of()))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void null_params_rejected() {
        ApiClient api = mock(ApiClient.class);
        var handler = new NotificationReadHandler(api);
        assertThatThrownBy(() -> handler.handle((WsSession) null, null))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void action_metadata() {
        ApiClient api = mock(ApiClient.class);
        var handler = new NotificationReadHandler(api);
        assertThat(handler.action()).isEqualTo("notification.read");
        assertThat(handler.requiresAuth()).isTrue();
        assertThat(handler.requiresAdmin()).isFalse();
    }
}
