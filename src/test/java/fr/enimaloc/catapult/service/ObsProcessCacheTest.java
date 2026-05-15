package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    void getSession_emptyByDefault() {
        assertThat(cache.getSession(user)).isSameAs(ObsSession.EMPTY);
    }

    @Test
    void update_storesProcessNames() {
        cache.update(user, session(Set.of(snap("hl2.exe", null), snap("steam.exe", null))));
        assertThat(processNames()).containsExactlyInAnyOrder("hl2", "steam");
    }

    @Test
    void update_normalizesProcessNames() {
        cache.update(user, session(Set.of(snap("HL2.EXE", null), snap("dota2.exe", null), snap("minecraft", null))));
        assertThat(processNames()).containsExactlyInAnyOrder("HL2", "dota2", "minecraft");
    }

    @Test
    void update_preservesWorkingDirectory() {
        cache.update(user, session(Set.of(snap("hl2.exe", "C:\\Games\\HL2"))));
        ObsSession.ProcessSnapshot snap = cache.getSession(user).processes().iterator().next();
        assertThat(snap.name()).isEqualTo("hl2");
        assertThat(snap.workingDirectory()).isEqualTo("C:\\Games\\HL2");
    }

    @Test
    void update_storesOsAndEnvironment() {
        ObsSession input = new ObsSession("WINDOWS", Map.of("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming"), Set.of(snap("game.exe", null)));
        cache.update(user, input);
        ObsSession stored = cache.getSession(user);
        assertThat(stored.os()).isEqualTo("WINDOWS");
        assertThat(stored.environment()).containsEntry("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming");
    }

    @Test
    void update_replacesExistingSession() {
        cache.update(user, session(Set.of(snap("old.exe", null))));
        cache.update(user, session(Set.of(snap("new.exe", null))));
        assertThat(processNames()).containsExactly("new");
    }

    @Test
    void clear_removesEntry() {
        cache.update(user, session(Set.of(snap("game.exe", null))));
        cache.clear(user);
        assertThat(cache.getSession(user)).isSameAs(ObsSession.EMPTY);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Set<String> processNames() {
        return cache.getSession(user).processes().stream()
                .map(ObsSession.ProcessSnapshot::name)
                .collect(Collectors.toSet());
    }

    private static ObsSession session(Set<ObsSession.ProcessSnapshot> procs) {
        return new ObsSession(null, Map.of(), procs);
    }

    private static ObsSession.ProcessSnapshot snap(String name, String workingDirectory) {
        return new ObsSession.ProcessSnapshot(name, workingDirectory, null);
    }
}
