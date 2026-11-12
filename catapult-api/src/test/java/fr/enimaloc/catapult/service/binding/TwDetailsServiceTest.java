package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.tw.TwResolverService.Signal;
import fr.enimaloc.catapult.service.tw.TwResolverService.SourceType;
import fr.enimaloc.catapult.service.tw.TwSeverityRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Why a binding has its TWs: the detection's sources, the streamer's or an admin's choice, the topics kept off. */
class TwDetailsServiceTest {

    private final BindingService bindings = mock(BindingService.class);
    private final GameMappingRepository mappings = mock(GameMappingRepository.class);
    private final TwDefinitionRepository definitions = mock(TwDefinitionRepository.class);
    private final TwDetailsService service = new TwDetailsService(bindings, mappings, definitions);
    private final UserAccount user = new UserAccount();
    private final UUID bindingId = UUID.randomUUID();
    private GameBinding binding;

    private static TwDefinition def(String id, String label, int order) {
        TwDefinition d = new TwDefinition();
        d.setId(id);
        d.setLabel(label);
        d.setSortOrder(order);
        return d;
    }

    private static TwSeverityRule.Verdict verdict(TwSeverityRule.Reason reason) {
        return new TwSeverityRule.Verdict(reason, "Depicted", "Minor", 10, 20);
    }

    private void explained(Signal... signals) {
        when(bindings.explainTws(user, bindingId)).thenReturn(Optional.of(
                new BindingService.TwExplanation(binding, new TwResolverService.Explanation(List.of(signals)))));
    }

    @BeforeEach
    void setUp() {
        binding = new GameBinding();
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("730");
        when(definitions.findAll()).thenReturn(List.of(def("gore", "Gore", 10), def("violence", "Violence", 20),
                def("spiders", "Spiders", 30)));
    }

    @Test
    void eachDetectedTw_comesWithItsSources_andTheTopicsKeptOffAreTold() {
        binding.getTws().addAll(List.of("violence", "gore"));
        explained(
                new Signal("violence", SourceType.STEAM_CONTENT, "2", null),
                new Signal("violence", SourceType.DTDD_TOPIC, "there's blood/gore", verdict(TwSeverityRule.Reason.NO_THRESHOLD)),
                new Signal("gore", SourceType.STEAM_KEYWORD, "gore", null),
                new Signal("gore", SourceType.DTDD_TOPIC, "there's blood/gore", verdict(TwSeverityRule.Reason.BELOW_VOTES)));

        TwDetailsDto details = service.details(user, bindingId).orElseThrow();

        assertThat(details.streamerChose()).isFalse();
        // By the TWs' order
        assertThat(details.tws()).extracting(TwDetailsDto.Tw::id).containsExactly("gore", "violence");
        assertThat(details.tws().get(0)).satisfies(gore -> {
            assertThat(gore.label()).isEqualTo("Gore");
            assertThat(gore.origin()).isEqualTo("DETECTED");
            assertThat(gore.sources()).singleElement().satisfies(s -> assertThat(s.value()).isEqualTo("gore"));
        });
        assertThat(details.tws().get(1).sources()).extracting(TwDetailsDto.Source::type)
                .containsExactly("STEAM_CONTENT", "DTDD_TOPIC");
        assertThat(details.keptOff()).singleElement().satisfies(s -> {
            assertThat(s.twId()).isEqualTo("gore");
            assertThat(s.twLabel()).isEqualTo("Gore");
            assertThat(s.severity().reason()).isEqualTo("BELOW_VOTES");
            assertThat(s.severity().minSeverity()).isEqualTo("Depicted");
            assertThat(s.severity().percentAtOrAbove()).isEqualTo(20);
        });
    }

    @Test
    void theStreamersChoice_isToldAsSuch_andWhatTheyTurnedOff_isShownOff() {
        binding.setTwOverride(true);
        binding.getTws().add("spiders");
        explained(new Signal("gore", SourceType.STEAM_KEYWORD, "gore", null));

        TwDetailsDto details = service.details(user, bindingId).orElseThrow();

        assertThat(details.streamerChose()).isTrue();
        assertThat(details.tws()).extracting(TwDetailsDto.Tw::id, TwDetailsDto.Tw::active, TwDetailsDto.Tw::origin)
                .containsExactly(org.assertj.core.api.Assertions.tuple("spiders", true, "STREAMER"),
                        org.assertj.core.api.Assertions.tuple("gore", false, "DETECTED"));
    }

    @Test
    void anAdminsTws_areToldAsSuch_andTheDetectionsOthersLeftOut() {
        binding.setStatus(GameBinding.Status.MAPPED);
        binding.getTws().add("spiders");
        GameMapping mapping = new GameMapping();
        mapping.setTwsOverridden(true);
        when(mappings.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(mapping));
        explained(new Signal("gore", SourceType.STEAM_KEYWORD, "gore", null));

        assertThat(service.details(user, bindingId).orElseThrow().tws())
                .extracting(TwDetailsDto.Tw::id, TwDetailsDto.Tw::origin)
                .containsExactly(org.assertj.core.api.Assertions.tuple("spiders", "ADMIN"));
    }

    @Test
    void aTwNothingSuggestsAnyMore_isToldSo() {
        binding.getTws().add("spiders");
        explained();

        assertThat(service.details(user, bindingId).orElseThrow().tws()).singleElement()
                .satisfies(t -> assertThat(t.origin()).isEqualTo("NO_SOURCE"));
    }

    @Test
    void anotherOnesBinding_hasNoDetails() {
        when(bindings.explainTws(user, bindingId)).thenReturn(Optional.empty());

        assertThat(service.details(user, bindingId)).isEmpty();
    }
}
