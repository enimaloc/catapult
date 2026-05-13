package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamStateServiceTest {

    private StreamStateService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new StreamStateService();
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void isLive_defaultsFalse() {
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void setLive_updatesState() {
        service.setLive(user, true);
        assertThat(service.isLive(user)).isTrue();
        service.setLive(user, false);
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void storePending_getPending_roundtrip() {
        GameBinding binding = new GameBinding();
        service.storePending(user, binding);
        assertThat(service.getPending(user)).contains(binding);
    }

    @Test
    void getPending_emptyByDefault() {
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clearPending_removesBinding() {
        service.storePending(user, new GameBinding());
        service.clearPending(user);
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clear_removesLiveAndPending() {
        service.setLive(user, true);
        service.storePending(user, new GameBinding());
        service.clear(user);
        assertThat(service.isLive(user)).isFalse();
        assertThat(service.getPending(user)).isEmpty();
    }
}
