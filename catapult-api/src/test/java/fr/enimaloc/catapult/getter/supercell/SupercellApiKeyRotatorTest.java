package fr.enimaloc.catapult.getter.supercell;

import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupercellApiKeyRotatorTest {

    @Mock SupercellApiKeyRepository repository;
    SupercellApiKeyRotator rotator;
    MutableClock clock = new MutableClock();

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new SupercellApiKeyRotator(repository, new SimpleMeterRegistry(), clock);
    }

    private void keys(SupercellApiKeyEntry... entries) {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entries));
        rotator.refreshKeys();
    }

    @Test
    void anEmptyPool_servesNoGame() {
        for (SupercellGame game : SupercellGame.values()) {
            assertThat(rotator.hasKeys(game)).isFalse();
            assertThat(rotator.nextKey(game)).isEmpty();
        }
    }

    @Test
    void eachGame_rotatesOverItsOwnKeysOnly_andAnUnknownGameIsIgnored() {
        keys(new SupercellApiKeyEntry("COC-1", "clash-of-clans"), new SupercellApiKeyEntry("COC-2", "clash-of-clans"),
                new SupercellApiKeyEntry("BS-1", "brawl-stars"), new SupercellApiKeyEntry("X", "hay-day"));

        Set<String> coc = new HashSet<>();
        for (int i = 0; i < 4; i++) coc.add(rotator.nextKey(SupercellGame.CLASH_OF_CLANS).orElseThrow());
        assertThat(coc).containsExactlyInAnyOrder("COC-1", "COC-2");
        assertThat(rotator.nextKey(SupercellGame.BRAWL_STARS)).contains("BS-1");
        assertThat(rotator.hasKeys(SupercellGame.CLASH_ROYALE)).isFalse();
        assertThat(rotator.availableKeyCount()).isEqualTo(3);
        assertThat(rotator.keyCount(SupercellGame.CLASH_OF_CLANS)).isEqualTo(2);
        assertThat(rotator.keyCount(SupercellGame.CLASH_ROYALE)).isZero();
    }

    @Test
    void aKeyRefusedFromThisIp_isSetAsideForItsGameOnly_untilTheTimeIsUp() {
        keys(new SupercellApiKeyEntry("COC-1", "clash-of-clans"), new SupercellApiKeyEntry("COC-2", "clash-of-clans"),
                new SupercellApiKeyEntry("CR-1", "clash-royale"));

        rotator.onIpRefused(SupercellGame.CLASH_OF_CLANS, "COC-1");

        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, "COC-1")).isTrue();
        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey(SupercellGame.CLASH_OF_CLANS)).contains("COC-2");
        assertThat(rotator.nextKey(SupercellGame.CLASH_ROYALE)).contains("CR-1");
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(rotator.getKeyBlockedUntil()).containsOnlyKeys("COC-1");

        clock.advance(SupercellApiKeyRotator.IP_REFUSED_FOR.plusSeconds(1));
        assertThat(rotator.blocked(SupercellGame.CLASH_OF_CLANS, "COC-1")).isFalse();
        assertThat(rotator.getKeyBlockedUntil()).isEmpty();
    }

    @Test
    void whenEveryKeyOfAGameIsSetAside_noKeyIsHandedOut() {
        keys(new SupercellApiKeyEntry("COC-1", "clash-of-clans"));

        rotator.onIpRefused(SupercellGame.CLASH_OF_CLANS, "COC-1");

        assertThat(rotator.hasKeys(SupercellGame.CLASH_OF_CLANS)).isTrue();
        assertThat(rotator.nextKey(SupercellGame.CLASH_OF_CLANS)).isEmpty();
    }

    @Test
    void usage_countsTheCallsOfAKey() {
        rotator.onCall("COC-1");
        rotator.onCall("COC-1");

        var usage = rotator.usage("COC-1");
        assertThat(usage.windowCalls()).isEqualTo(2);
        // the window follows the rotator's clock
        clock.advance(Duration.ofMinutes(2));
        assertThat(rotator.usage("COC-1").windowCalls()).isZero();
        assertThat(usage.windowSeconds()).isEqualTo(60);
        assertThat(usage.periodCalls()).isEqualTo(2);
    }
}
