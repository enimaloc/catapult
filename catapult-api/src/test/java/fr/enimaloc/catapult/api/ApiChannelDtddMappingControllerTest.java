package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.domain.DtddMappingProposal.Status;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiChannelDtddMappingControllerTest {

    @Mock DtddGameMappingRepository mappingRepo;
    @Mock DtddGameCacheRepository gameRepo;
    @Mock DtddSearchCacheRepository searchRepo;
    @Mock DtddMappingProposalRepository proposalRepo;
    @Mock DtddApiClient apiClient;

    ApiChannelDtddMappingController controller;
    UserAccount user;

    @BeforeEach
    void setUp() {
        controller = new ApiChannelDtddMappingController(mappingRepo, gameRepo, searchRepo, proposalRepo, apiClient);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void status_canValidateDirectly_whenUnverifiedAndNoOtherProposals() {
        when(mappingRepo.findById("100")).thenReturn(Optional.of(
            new DtddGameMapping("100", 4521L, 0.71, false, Instant.now())));
        when(proposalRepo.countByIgdbIdAndStatus("100", Status.PENDING)).thenReturn(0L);
        when(proposalRepo.findFirstByProposerAndIgdbIdAndStatus(user, "100", Status.PENDING))
            .thenReturn(Optional.empty());
        when(gameRepo.findById(4521L)).thenReturn(Optional.empty());

        var status = controller.status("100", user);
        assertThat(status.canValidateDirectly()).isTrue();
    }

    @Test
    void status_cannotValidate_whenVerified() {
        when(mappingRepo.findById("100")).thenReturn(Optional.of(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now())));
        when(gameRepo.findById(4521L)).thenReturn(Optional.empty());
        var status = controller.status("100", user);
        assertThat(status.canValidateDirectly()).isFalse();
    }

    @Test
    void validate_marksMappingVerified_whenAllowed() {
        DtddGameMapping mapping = new DtddGameMapping("100", 4521L, 0.71, false, Instant.now());
        when(mappingRepo.findById("100")).thenReturn(Optional.of(mapping));
        when(proposalRepo.countByIgdbIdAndStatus("100", Status.PENDING)).thenReturn(0L);

        controller.validate(new ApiChannelDtddMappingController.ValidateRequest("100"), user);

        assertThat(mapping.isVerified()).isTrue();
        assertThat(mapping.getConfidence()).isEqualTo(1.0);
        verify(mappingRepo).save(mapping);
    }

    @Test
    void validate_rejects_whenAlreadyVerified() {
        when(mappingRepo.findById("100")).thenReturn(Optional.of(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now())));
        assertThatThrownBy(() -> controller.validate(new ApiChannelDtddMappingController.ValidateRequest("100"), user))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void propose_createsPendingProposal() {
        controller.propose(new ApiChannelDtddMappingController.ProposeRequest("100", 1287L, "wrong game"), user);
        verify(proposalRepo).save(any(DtddMappingProposal.class));
    }

    @Test
    void search_combinesLocalAndRemote() {
        when(gameRepo.searchByNameLike(eq("stardew"), any(PageRequest.class))).thenReturn(List.of(
            new DtddGameCache(4521L, "Stardew Valley", "s-v", "Video Game", null, Instant.now())));
        when(searchRepo.findById("stardew")).thenReturn(Optional.empty());
        when(apiClient.search("stardew")).thenReturn(Optional.of(List.of(
            new DtddSearchResult(4521L, "Stardew Valley", "s-v", "Video Game", null),
            new DtddSearchResult(7777L, "Stardew Mod", "mod", "Video Game", null)
        )));

        var results = controller.search("stardew");

        assertThat(results.results()).extracting(r -> r.dtddId()).containsExactlyInAnyOrder(4521L, 7777L);
    }
}
