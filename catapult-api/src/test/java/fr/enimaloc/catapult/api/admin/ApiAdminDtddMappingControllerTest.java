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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminDtddMappingControllerTest {

    @Mock DtddMappingProposalRepository proposalRepo;
    @Mock DtddGameMappingRepository mappingRepo;
    @Mock DtddTopicsCacheRepository topicsRepo;

    ApiAdminDtddMappingController controller;
    UserAccount admin;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminDtddMappingController(proposalRepo, mappingRepo, topicsRepo);
        admin = new UserAccount();
        admin.setId(UUID.randomUUID());
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

        controller.approve(propId, admin);

        assertThat(existing.getDtddId()).isEqualTo(1287L);
        assertThat(existing.isVerified()).isTrue();
        assertThat(existing.getConfidence()).isEqualTo(1.0);
        verify(topicsRepo).deleteById(4521L); // old invalidated
        assertThat(proposal.getStatus()).isEqualTo(Status.APPROVED);
        assertThat(proposal.getResolver()).isEqualTo(admin);
    }

    @Test
    void reject_marksProposalRejected_withoutTouchingMapping() {
        UUID propId = UUID.randomUUID();
        DtddMappingProposal proposal = new DtddMappingProposal();
        proposal.setId(propId);
        proposal.setStatus(Status.PENDING);
        when(proposalRepo.findById(propId)).thenReturn(Optional.of(proposal));

        controller.reject(propId, new RejectRequest("not the right game"), admin);

        assertThat(proposal.getStatus()).isEqualTo(Status.REJECTED);
        verify(mappingRepo, never()).save(any());
    }
}
