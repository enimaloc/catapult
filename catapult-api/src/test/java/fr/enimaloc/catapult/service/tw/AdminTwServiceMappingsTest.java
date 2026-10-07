package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.domain.tw.TwDtddTopicMapping;
import fr.enimaloc.catapult.domain.tw.TwIgdbDescriptorMapping;
import fr.enimaloc.catapult.domain.tw.TwSteamContentIdMapping;
import fr.enimaloc.catapult.domain.tw.TwSteamKeyword;
import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwIgdbDescriptorMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamContentIdMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwSteamKeywordRepository;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AdminTwService: definition edits and the per-TW signal mappings they own. */
class AdminTwServiceMappingsTest {

    private final TwDefinitionRepository definitions = mock(TwDefinitionRepository.class);
    private final TwDtddTopicMappingRepository dtdd = mock(TwDtddTopicMappingRepository.class);
    private final TwIgdbDescriptorMappingRepository igdb = mock(TwIgdbDescriptorMappingRepository.class);
    private final TwSteamContentIdMappingRepository steamIds = mock(TwSteamContentIdMappingRepository.class);
    private final TwSteamKeywordRepository keywords = mock(TwSteamKeywordRepository.class);
    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SteamStoreService steamStore = mock(SteamStoreService.class);
    private final AdminTwService service = new AdminTwService(
            definitions, dtdd, igdb, steamIds, keywords, bindings, events, steamStore);

    private TwDefinition gore;

    @BeforeEach
    void setUp() {
        gore = new TwDefinition();
        gore.setId("gore");
        gore.setLabel("Gore");
        when(definitions.existsById(anyString())).thenReturn(false);
        when(definitions.existsById("gore")).thenReturn(true);
        when(definitions.findById("gore")).thenReturn(Optional.of(gore));
    }

    private static TwSteamKeyword keyword(String value) {
        TwSteamKeyword keyword = new TwSteamKeyword();
        keyword.setTwId("gore");
        keyword.setKeyword(value);
        return keyword;
    }

    private void changedTimes(int times) {
        verify(events, times(times)).publishEvent(any(TwDefinitionsChangedEvent.class));
    }

    @Test
    void listAndGet() {
        when(definitions.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(gore));

        assertThat(service.list()).containsExactly(gore);
        assertThat(service.get("gore")).contains(gore);
    }

    @Test
    void create_refusesDuplicatesAndBadLabels() {
        assertThatThrownBy(() -> service.create("gore", "Gore", null, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create("new_tw", " ", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("new_tw", null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("new_tw", "x".repeat(81), null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(null, "Label", null, 0)).isInstanceOf(IllegalArgumentException.class);
        verify(definitions, never()).save(any());
    }

    @Test
    void update_changesOnlyTheGivenFields() {
        gore.setDescription("old");
        gore.setSortOrder(3);

        service.update("gore", " Blood ", null, null, false);

        assertThat(gore.getLabel()).isEqualTo("Blood");
        assertThat(gore.getDescription()).isEqualTo("old");
        assertThat(gore.getSortOrder()).isEqualTo(3);
        assertThat(gore.isEnabled()).isFalse();

        service.update("gore", null, "new", 1, null);
        assertThat(gore.getLabel()).isEqualTo("Blood");
        assertThat(gore.getDescription()).isEqualTo("new");
        assertThat(gore.getSortOrder()).isEqualTo(1);
        changedTimes(2);
    }

    @Test
    void update_refusesUnknownTwsAndBadLabels() {
        assertThatThrownBy(() -> service.update("nope", null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("gore", "", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_whenUnused() {
        when(bindings.existsByTwsContaining("gore")).thenReturn(false);

        service.delete("gore");

        verify(definitions).deleteById("gore");
        changedTimes(1);
    }

    @Test
    void dtddTopics_areReplaced_skippingBlankAndOverlongOnes() {
        Set<String> topics = new LinkedHashSet<>(List.of(" a dog dies ", " ", "x".repeat(201)));

        service.replaceDtddTopics("gore", topics);

        verify(dtdd).deleteAllByTwId("gore");
        ArgumentCaptor<TwDtddTopicMapping> saved = ArgumentCaptor.forClass(TwDtddTopicMapping.class);
        verify(dtdd).save(saved.capture());
        assertThat(saved.getValue().getDtddTopicName()).isEqualTo("a dog dies");
        changedTimes(1);
    }

    @Test
    void igdbDescriptors_areReplaced() {
        service.replaceIgdbDescriptors("gore", Set.of(29L));

        verify(igdb).deleteAllByTwId("gore");
        ArgumentCaptor<TwIgdbDescriptorMapping> saved = ArgumentCaptor.forClass(TwIgdbDescriptorMapping.class);
        verify(igdb).save(saved.capture());
        assertThat(saved.getValue().getDescriptorId()).isEqualTo(29L);
        assertThat(saved.getValue().getTwId()).isEqualTo("gore");
    }

    @Test
    void steamContentIds_areReplaced_andMustBeKnown() {
        service.replaceSteamContentIds("gore", Set.of(2));
        ArgumentCaptor<TwSteamContentIdMapping> saved = ArgumentCaptor.forClass(TwSteamContentIdMapping.class);
        verify(steamIds).save(saved.capture());
        assertThat(saved.getValue().getSteamContentId()).isEqualTo(2);

        assertThatThrownBy(() -> service.replaceSteamContentIds("gore", Set.of(9))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void steamKeywords_areReplaced_normalizedAndDeduplicated() {
        service.replaceSteamKeywords("gore", new LinkedHashSet<>(List.of("Blood", " blood ", "x", "Gore")));

        verify(keywords).deleteAllByTwId("gore");
        ArgumentCaptor<TwSteamKeyword> saved = ArgumentCaptor.forClass(TwSteamKeyword.class);
        verify(keywords, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TwSteamKeyword::getKeyword).containsExactly("blood", "gore");
    }

    @Test
    void steamKeywords_areListedSorted() {
        when(keywords.findAllByTwId("gore")).thenReturn(List.of(keyword("gore"), keyword("blood")));

        assertThat(service.listSteamKeywords("gore")).containsExactly("blood", "gore");
    }

    @Test
    void addingAKnownKeyword_isANoOp() {
        when(keywords.findAllByTwId("gore")).thenReturn(List.of(keyword("blood")));

        service.addSteamKeyword("gore", " BLOOD ");

        verify(keywords, never()).save(any());
        changedTimes(0);
    }

    @Test
    void removingAnInvalidKeyword_isANoOp() {
        service.removeSteamKeyword("gore", "x");

        verify(keywords, never()).findAllByTwId(anyString());
        changedTimes(0);
    }

    @Test
    void everyMappingEdit_needsAnExistingTw() {
        assertThatThrownBy(() -> service.replaceDtddTopics("nope", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replaceIgdbDescriptors("nope", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replaceSteamContentIds("nope", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replaceSteamKeywords("nope", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listSteamKeywords("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addSteamKeyword("nope", "blood")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.removeSteamKeyword("nope", "blood")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.testSteamSignals("nope", "1", null)).isInstanceOf(IllegalArgumentException.class);
        changedTimes(0);
    }

    @Test
    void signalTest_withoutDraft_orStoreData() {
        when(steamStore.fetchTwSignals(List.of("1"))).thenReturn(Map.of());
        when(keywords.findAllByTwId("gore")).thenReturn(List.of(keyword("blood")));
        when(steamIds.findAllByTwId("gore")).thenReturn(List.of());

        AdminTwService.SteamSignalTestResult result = service.testSteamSignals("gore", "1", null);

        assertThat(result.notes()).isEmpty();
        assertThat(result.matchedKeywords()).isEmpty();
        assertThat(result.draftKeywordMatch()).isNull();
        assertThat(result.matchedContentDescriptorIds()).isEmpty();
    }
}
