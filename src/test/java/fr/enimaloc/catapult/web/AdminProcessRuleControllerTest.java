package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminProcessRuleControllerTest {

    @Mock private ProcessBindingRepository processBindingRepository;
    @InjectMocks private AdminProcessRuleController controller;

    @Test
    void addRule_exactMatch_savesWithoutRegexFlag() {
        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);

        controller.addRule("hl2", false, "51", "Half-Life 2", new RedirectAttributesModelMap());

        verify(processBindingRepository).save(captor.capture());
        ProcessBinding saved = captor.getValue();
        assertThat(saved.getProcessName()).isEqualTo("hl2");
        assertThat(saved.isRegex()).isFalse();
        assertThat(saved.getTwitchGameId()).isEqualTo("51");
        assertThat(saved.getTwitchGameName()).isEqualTo("Half-Life 2");
        assertThat(saved.getUser()).isNull();
    }

    @Test
    void addRule_validRegex_savesWithRegexFlag() {
        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);

        controller.addRule("hl.*", true, "51", "Half-Life", new RedirectAttributesModelMap());

        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().isRegex()).isTrue();
        assertThat(captor.getValue().getProcessName()).isEqualTo("hl.*");
    }

    @Test
    void addRule_invalidRegex_doesNotSave() {
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        controller.addRule("[invalid(", true, "51", "Game", redirectAttributes);

        verify(processBindingRepository, never()).save(any());
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("error");
    }

    @Test
    void deleteRule_globalRule_deletes() {
        ProcessBinding rule = new ProcessBinding();
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deleteRule(id);

        verify(processBindingRepository).delete(rule);
    }

    @Test
    void deleteRule_userBinding_doesNotDelete() {
        ProcessBinding rule = new ProcessBinding();
        rule.setUser(new fr.enimaloc.catapult.domain.UserAccount());
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deleteRule(id);

        verify(processBindingRepository, never()).delete(any());
    }

    @Test
    void addPredicate_globalRule_savesPredicate() {
        ProcessBinding rule = new ProcessBinding();
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.addPredicate(id,
                ProcessPredicate.PredicateType.WORKING_DIRECTORY,
                ProcessPredicate.Connector.AND,
                null,
                "C:\\Games",
                ProcessPredicate.OsTarget.WINDOWS);

        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);
        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().getPredicates()).hasSize(1);
        assertThat(captor.getValue().getPredicates().get(0).getValue()).isEqualTo("C:\\Games");
    }

    @Test
    void deletePredicate_globalRule_removesPredicate() {
        ProcessBinding rule = new ProcessBinding();
        UUID predId = UUID.randomUUID();
        ProcessPredicate pred = new ProcessPredicate();
        pred.setId(predId);
        pred.setType(ProcessPredicate.PredicateType.WORKING_DIRECTORY);
        pred.setValue("C:\\Games");
        pred.setOsTarget(ProcessPredicate.OsTarget.ALL);
        pred.setConnector(ProcessPredicate.Connector.AND);
        rule.getPredicates().add(pred);
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deletePredicate(id, predId);

        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);
        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().getPredicates()).isEmpty();
    }
}
