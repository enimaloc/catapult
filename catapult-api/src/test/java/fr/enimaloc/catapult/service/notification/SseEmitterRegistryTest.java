package fr.enimaloc.catapult.service.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRegistryTest {

    @Test
    void register_storesEmitter() {
        SseEmitterRegistry reg = new SseEmitterRegistry();
        UUID user = UUID.randomUUID();

        SseEmitter e = reg.register(user);

        assertThat(reg.connectionCount(user)).isEqualTo(1);
        assertThat(e).isNotNull();
    }

    @Test
    void register_multipleEmitters_perUser() {
        SseEmitterRegistry reg = new SseEmitterRegistry();
        UUID user = UUID.randomUUID();

        reg.register(user);
        reg.register(user);

        assertThat(reg.connectionCount(user)).isEqualTo(2);
    }

    @Test
    void pushToUser_doesNotThrowWhenNoEmitter() {
        SseEmitterRegistry reg = new SseEmitterRegistry();
        reg.pushToUser(UUID.randomUUID(), "any", new Object());
    }
}
