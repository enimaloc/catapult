package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObsProcessCacheTest {

    private ObsProcessCache cache;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        cache = new ObsProcessCache();
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getProcesses_emptyByDefault() {
        assertThat(cache.getProcesses(user)).isEmpty();
    }

    @Test
    void update_storesProcesses() {
        cache.update(user, Set.of("hl2.exe", "steam.exe"));
        assertThat(cache.getProcesses(user)).containsExactlyInAnyOrder("hl2.exe", "steam.exe");
    }

    @Test
    void update_replacesExistingProcesses() {
        cache.update(user, Set.of("old.exe"));
        cache.update(user, Set.of("new.exe"));
        assertThat(cache.getProcesses(user)).containsExactly("new.exe");
    }

    @Test
    void clear_removesEntry() {
        cache.update(user, Set.of("game.exe"));
        cache.clear(user);
        assertThat(cache.getProcesses(user)).isEmpty();
    }
}
