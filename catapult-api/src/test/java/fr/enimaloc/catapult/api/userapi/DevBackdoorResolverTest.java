package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.XboxStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dev "backdoor" UUIDs: decoding them into fake detected games, and encoding bindings back. */
class DevBackdoorResolverTest {

    private final SteamStoreService steam = mock(SteamStoreService.class);
    private final XboxStoreService xbox = mock(XboxStoreService.class);
    private final DevBackdoorResolver resolver = new DevBackdoorResolver(steam, xbox);

    @Test
    void steamUuids_decodeToTheAppAndItsStoreName() {
        SteamStoreService.SteamStorePage page = mock(SteamStoreService.SteamStorePage.class);
        when(page.name()).thenReturn("Cyberpunk 2077");
        when(steam.fetchData("1091500", Locale.ENGLISH)).thenReturn(Optional.of(page));

        UUID uuid = resolver.encode(GameBinding.SourceType.STEAM, "1091500").orElseThrow();

        assertThat(uuid).isEqualTo(new UUID(0L, (1L << 48) | 1091500L));
        assertThat(resolver.resolve(uuid)).contains(new DetectedGame("1091500", GameBinding.SourceType.STEAM, "Cyberpunk 2077"));
    }

    @Test
    void xboxUuids_carryAFullBase36ProductId() {
        when(xbox.fetchProduct(anyString(), any())).thenReturn(Optional.empty());

        UUID uuid = resolver.encode(GameBinding.SourceType.XBOX, "9NBLGGH2JHXJ").orElseThrow();

        assertThat(uuid.getMostSignificantBits()).isNotZero();
        assertThat(resolver.resolve(uuid)).contains(new DetectedGame("9NBLGGH2JHXJ", GameBinding.SourceType.XBOX, "9NBLGGH2JHXJ"));
    }

    @Test
    void xboxNames_comeFromTheStore() {
        XboxStoreService.XboxProduct product = mock(XboxStoreService.XboxProduct.class);
        when(product.title()).thenReturn("Halo Infinite");
        when(xbox.fetchProduct("9PP5G1F0C2B6", Locale.ENGLISH)).thenReturn(Optional.of(product));

        UUID uuid = resolver.encode(GameBinding.SourceType.XBOX, "9pp5g1f0c2b6").orElseThrow();

        assertThat(resolver.resolve(uuid).map(DetectedGame::getSourceName)).contains("Halo Infinite");
    }

    @Test
    void unknownSteamApps_fallBackToTheirId() {
        when(steam.fetchData(anyString(), any())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(new UUID(0L, (1L << 48) | 42L)).map(DetectedGame::getSourceName)).contains("42");
    }

    @Test
    void otherNibbles_decodeAsManualGames() {
        assertThat(resolver.resolve(new UUID(0L, (7L << 48) | 99L)))
                .contains(new DetectedGame("99", GameBinding.SourceType.MANUAL, "99"));
    }

    @Test
    void realUuids_areNotBackdoors() {
        assertThat(resolver.resolve(UUID.randomUUID())).isEmpty();
        assertThat(resolver.resolve(new UUID(1L << 20, 1L))).isEmpty();
        assertThat(resolver.resolve(new UUID(0L, 1L << 60))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", "not-a-number", "281474976710656"})
    void unencodableSteamIds(String sourceId) {
        assertThat(resolver.encode(GameBinding.SourceType.STEAM, sourceId)).isEmpty();
    }

    @Test
    void unencodableTypesAndIds() {
        assertThat(resolver.encode(GameBinding.SourceType.STEAM, null)).isEmpty();
        assertThat(resolver.encode(GameBinding.SourceType.XBOX, "not base36!")).isEmpty();
        assertThat(resolver.encode(GameBinding.SourceType.XBOX, "-5")).isEmpty();
        assertThat(resolver.encode(GameBinding.SourceType.MANUAL, "1")).isEmpty();
        assertThat(resolver.encode(GameBinding.SourceType.BATTLENET, "1")).isEmpty();
    }
}
