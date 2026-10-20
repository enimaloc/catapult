package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.RejectRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal.Status;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminDtddMappingControllerTest {

    @Mock DtddMappingProposalRepository proposalRepo;
    @Mock DtddGameMappingRepository mappingRepo;
    @Mock DtddTopicsCacheRepository topicsRepo;
    @Mock fr.enimaloc.catapult.service.dtdd.DtddMappingEvents mappingEvents;
    @Mock fr.enimaloc.catapult.api.ApiUserResolver userResolver;
    @Mock org.springframework.security.oauth2.jwt.Jwt jwt;

    ApiAdminDtddMappingController controller;
    UserAccount admin;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminDtddMappingController(proposalRepo, mappingRepo, topicsRepo, mappingEvents, userResolver);
        admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        lenient().when(userResolver.viewer(jwt)).thenReturn(admin);
    }

    @Test
    void pending_listsTheProposalsAsTheWebReadsThem() {
        UserAccount proposer = new UserAccount();
        proposer.setTwitchUsername("viewer");
        DtddMappingProposal proposal = new DtddMappingProposal();
        proposal.setId(UUID.randomUUID());
        proposal.setIgdbId("100");
        proposal.setProposedDtddId(1287L);
        proposal.setProposer(proposer);
        proposal.setReason("same game");
        proposal.setStatus(Status.PENDING);
        proposal.setCreatedAt(Instant.parse("2026-10-16T10:00:00Z"));
        when(proposalRepo.findByStatusOrderByCreatedAtAsc(Status.PENDING)).thenReturn(java.util.List.of(proposal));

        assertThat(controller.pending("PENDING")).containsExactly(new fr.enimaloc.catapult.common.dto.admin.DtddProposalDto(
                proposal.getId(), proposal.getCreatedAt(), "100", 1287L,
                new fr.enimaloc.catapult.common.dto.admin.DtddProposalDto.Proposer("viewer"), "same game", "PENDING"));
    }

    @Test
    void pending_refusesAnUnknownStatus_with400() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.pending("NOPE"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void approve_updatesMapping_andInvalidatesOldTopics() {
        UUID propId = UUID.randomUUID();
        DtddMappingProposal proposal = new DtddMappingProposal();
        proposal.setId(propId);
        proposal.setIgdbId("100");
        proposal.setProposedDtddId(1287L);
        proposal.setStatus(Status.PENDING);
        when(proposalRepo.findById(propId)).thenReturn(Optional.of(proposal));

        DtddGameMapping existing = new DtddGameMapping("100", 4521L, 0.71, false, Instant.now());
        when(mappingRepo.findById("100")).thenReturn(Optional.of(existing));

        controller.approve(propId, jwt);

        assertThat(existing.getDtddId()).isEqualTo(1287L);
        assertThat(existing.isVerified()).isTrue();
        assertThat(existing.getConfidence()).isEqualTo(1.0);
        verify(topicsRepo).deleteById(4521L); // old invalidated
        assertThat(proposal.getStatus()).isEqualTo(Status.APPROVED);
        assertThat(proposal.getResolver()).isEqualTo(admin);
        verify(mappingEvents).mappingChanged("100");
    }

    @Test
    void reject_marksProposalRejected_withoutTouchingMapping() {
        UUID propId = UUID.randomUUID();
        DtddMappingProposal proposal = new DtddMappingProposal();
        proposal.setId(propId);
        proposal.setStatus(Status.PENDING);
        when(proposalRepo.findById(propId)).thenReturn(Optional.of(proposal));

        controller.reject(propId, new RejectRequest("not the right game"), jwt);

        assertThat(proposal.getStatus()).isEqualTo(Status.REJECTED);
        verify(mappingRepo, never()).save(any());
    }
}
