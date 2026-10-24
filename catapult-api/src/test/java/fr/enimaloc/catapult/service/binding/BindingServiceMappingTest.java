package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BindingService with an admin game mapping: it sets the category of every binding but the MANUAL ones. */
class BindingServiceMappingTest {

    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final GameMappingRepository mappings = mock(GameMappingRepository.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbDetails = mock(IgdbGameDetailsService.class);
    private final TwitchService twitch = mock(TwitchService.class);
    private final TwResolverService twResolver = mock(TwResolverService.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final BindingService service =
            new BindingService(bindings, igdb, igdbDetails, twitch, twResolver, gameState, mappings,
                    mock(CustomBindingActivations.class));

    private final DetectedGame cs2 = new DetectedGame("730", GameBinding.SourceType.STEAM, "Counter-Strike 2");
    private UserAccount user;
    private GameMapping mapping;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());

        mapping = new GameMapping();
        mapping.setSourceType(GameBinding.SourceType.STEAM);
        mapping.setSourceId("730");
        mapping.setTwitchGameId("32399");
        mapping.setTwitchGameName("Counter-Strike");

        when(bindings.save(any())).thenAnswer(call -> call.getArgument(0));
        when(bindings.findByIdAndUser(any(), any())).thenReturn(Optional.empty());
        when(mappings.findBySourceTypeAndSourceId(any(), any())).thenReturn(Optional.empty());
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(mapping));

        // IGDB knows the game: its suggestions are what a mapping that replaces nothing keeps.
        when(igdb.findByExternalAppId(GameBinding.SourceType.STEAM, "730"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("igdb-1", "Counter-Strike 2")));
        when(igdb.suggestCcls("igdb-1")).thenReturn(Set.of("ViolentGraphic"));
        when(twResolver.suggest(any())).thenReturn(Set.of("tw-igdb"));
        when(igdbDetails.getDetails(anyString())).thenReturn(Optional.empty());
        when(igdb.findTwitchGameId("igdb-1")).thenReturn(Optional.of("igdb-twitch"));
        when(twitch.findCategoryIdByName(any(), anyString())).thenReturn(Optional.empty());
    }

    private GameBinding binding(GameBinding.Status status) {
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("730");
        binding.setSourceName("Counter-Strike 2");
        binding.setStatus(status);
        binding.setTwitchGameId("old");
        binding.setTwitchGameName("Old");
        binding.getCcls().add("Gambling");
        binding.getTws().add("tw-old");
        when(bindings.findByIdAndUser(binding.getId(), user)).thenReturn(Optional.of(binding));
        return binding;
    }

    @Test
    void newDetection_withMapping_isMapped() {
        when(bindings.findByUserAndSourceIdAndSourceType(user, "730", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.empty());

        GameBinding created = service.resolveOrCreate(user, cs2);

        assertThat(created.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        assertThat(created.getTwitchGameId()).isEqualTo("32399");
        assertThat(created.getTwitchGameName()).isEqualTo("Counter-Strike");
        verify(bindings).save(created);
    }

    @Test
    void mapping_withoutCclOverride_keepsIgdbCcls() {
        GameBinding binding = binding(GameBinding.Status.AUTO);

        service.applyMapping(binding, mapping);

        assertThat(binding.getCcls()).containsExactly("ViolentGraphic");
        assertThat(binding.getTws()).containsExactly("tw-igdb");
    }

    @Test
    void mapping_withCclOverride_usesAdminCcls() {
        mapping.setCclsOverridden(true);
        mapping.setTwsOverridden(true);
        mapping.getTws().add("tw-admin");
        GameBinding binding = binding(GameBinding.Status.AUTO);

        service.applyMapping(binding, mapping);

        // Replaced by an empty set: none at all, not IGDB's.
        assertThat(binding.getCcls()).isEmpty();
        assertThat(binding.getTws()).containsExactly("tw-admin");
        verify(igdb, never()).suggestCcls(anyString());
    }

    @Test
    void twOverride_isRespected() {
        mapping.setTwsOverridden(true);
        mapping.getTws().add("tw-admin");
        GameBinding binding = binding(GameBinding.Status.AUTO);
        binding.setTwOverride(true);

        boolean changed = service.applyMapping(binding, mapping);

        assertThat(changed).isTrue();
        assertThat(binding.getTws()).containsExactly("tw-old");
        assertThat(binding.isTwOverride()).isTrue();
        assertThat(binding.getTwitchGameId()).isEqualTo("32399");
    }

    @Test
    void applyMapping_neverTouchesManual() {
        mapping.setCclsOverridden(true);
        GameBinding binding = binding(GameBinding.Status.MANUAL);

        boolean changed = service.applyMapping(binding, mapping);

        assertThat(changed).isFalse();
        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(binding.getTwitchGameId()).isEqualTo("old");
        assertThat(binding.getTwitchGameName()).isEqualTo("Old");
        assertThat(binding.getCcls()).containsExactly("Gambling");
        assertThat(binding.getTws()).containsExactly("tw-old");
        verify(bindings, never()).save(any());
    }

    @Test
    void applyMapping_keepsStreamerToggles() {
        GameBinding binding = binding(GameBinding.Status.INCOMPLETE);
        binding.setIgnored(true);
        binding.setCclEnabled(false);
        binding.setTwEnabled(false);

        service.applyMapping(binding, mapping);

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        assertThat(binding.isIgnored()).isTrue();
        assertThat(binding.isCclEnabled()).isFalse();
        assertThat(binding.isTwEnabled()).isFalse();
    }

    @Test
    void applyMapping_alreadyUpToDate_reportsNoChange() {
        GameBinding binding = binding(GameBinding.Status.AUTO);
        service.applyMapping(binding, mapping);

        assertThat(service.applyMapping(binding, mapping)).isFalse();
    }

    @Test
    void refreshIncomplete_keepsTheMapping() {
        GameBinding binding = binding(GameBinding.Status.INCOMPLETE);
        when(bindings.findAllByStatusAndIgnoredFalse(GameBinding.Status.INCOMPLETE)).thenReturn(List.of(binding));

        service.refreshIncompleteBindings();

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        assertThat(binding.getTwitchGameId()).isEqualTo("32399");
        verify(bindings).save(binding);
        // The category comes from the mapping: none of IGDB's category lookups run.
        verify(igdbDetails, never()).getDetails(anyString());
        verify(igdb, never()).findTwitchGameId(anyString());
        verify(twitch, never()).findCategoryIdByName(any(), anyString());
    }

    @Test
    void resetTws_keepsTheMappedCategory() {
        GameBinding binding = binding(GameBinding.Status.MAPPED);
        binding.setTwitchGameId("32399");
        binding.setTwitchGameName("Counter-Strike");
        binding.setTwOverride(true);

        service.resetTws(user, binding.getId());

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        assertThat(binding.getTwitchGameId()).isEqualTo("32399");
        assertThat(binding.isTwOverride()).isFalse();
        assertThat(binding.getTws()).containsExactly("tw-igdb");
        verify(igdb, never()).findTwitchGameId(anyString());
    }

    @Test
    void resetTws_onAManualBinding_followsTheMapping() {
        GameBinding binding = binding(GameBinding.Status.MANUAL);
        binding.setTwOverride(true);

        service.resetTws(user, binding.getId());

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        assertThat(binding.getTwitchGameId()).isEqualTo("32399");
        assertThat(binding.getTwitchGameName()).isEqualTo("Counter-Strike");
        assertThat(binding.isTwOverride()).isFalse();
        assertThat(binding.getTws()).containsExactly("tw-igdb");
        verify(bindings).save(binding);
        verify(igdb, never()).findTwitchGameId(anyString());
    }

    @Test
    void resetTws_onAManualBinding_withoutMapping_goesBackToIgdb() {
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.empty());
        GameBinding binding = binding(GameBinding.Status.MANUAL);
        binding.setTwOverride(true);

        service.resetTws(user, binding.getId());

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.AUTO);
        assertThat(binding.getTwitchGameId()).isEqualTo("igdb-twitch");
        assertThat(binding.getTwitchGameName()).isEqualTo("Counter-Strike 2");
        assertThat(binding.getTws()).containsExactly("tw-igdb");
    }

    @Test
    void resolveWithoutMapping_fallsBackToIgdb() {
        GameBinding found = binding(GameBinding.Status.MAPPED);

        GameBinding resolved = service.resolveWithoutMapping(found);

        assertThat(resolved.getStatus()).isEqualTo(GameBinding.Status.AUTO);
        assertThat(resolved.getTwitchGameId()).isEqualTo("igdb-twitch");
        assertThat(resolved.getTwitchGameName()).isEqualTo("Counter-Strike 2");
        verify(bindings).save(resolved);

        // Unknown to IGDB's Twitch links: back to INCOMPLETE rather than the old mapped category.
        when(igdb.findTwitchGameId("igdb-1")).thenReturn(Optional.empty());
        GameBinding lost = binding(GameBinding.Status.MAPPED);

        assertThat(service.resolveWithoutMapping(lost).getStatus()).isEqualTo(GameBinding.Status.INCOMPLETE);
    }
}
