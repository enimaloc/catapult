package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockDtddProposalsStoreTest {
    private final MockDtddProposalsStore store = new MockDtddProposalsStore();

    private UUID firstPending() {
        return store.list("PENDING").getFirst().id();
    }

    @Test
    void isSeededWithTwoPendingOneApprovedOneRejected() {
        assertThat(store.list("PENDING")).hasSize(2);
        assertThat(store.list("APPROVED")).hasSize(1);
        assertThat(store.list("REJECTED")).hasSize(1);
    }

    @Test
    void anUnknownStatusListsThePendingOnes() {
        assertThat(store.list("nope")).isEqualTo(store.list("PENDING"));
        assertThat(store.list(null)).isEqualTo(store.list("PENDING"));
    }

    @Test
    void approving_movesTheProposalToApproved() {
        UUID id = firstPending();

        assertThat(store.approve(id)).isEqualTo(204);

        assertThat(store.list("PENDING")).extracting(DtddProposalDto::id).doesNotContain(id);
        assertThat(store.list("APPROVED")).extracting(DtddProposalDto::id).contains(id);
    }

    @Test
    void rejecting_movesTheProposalToRejected_whateverTheReason() {
        UUID id = firstPending();

        assertThat(store.reject(id)).isEqualTo(204);

        assertThat(store.list("REJECTED")).extracting(DtddProposalDto::id).contains(id);
    }

    @Test
    void resolvingTwice_is409_andAnUnknownOne_is404() {
        UUID id = firstPending();
        store.approve(id);

        assertThat(store.approve(id)).isEqualTo(409);
        assertThat(store.reject(id)).isEqualTo(409);
        assertThat(store.approve(UUID.randomUUID())).isEqualTo(404);
        assertThat(store.reject(UUID.randomUUID())).isEqualTo(404);
    }

    @Test
    void aNewProposal_isPending_andKeepsItsProposerAndReason() {
        store.propose("77", 5L, "wrong game", "alice");

        List<DtddProposalDto> pending = store.list("PENDING");
        assertThat(pending).hasSize(3);
        DtddProposalDto added = pending.getLast();
        assertThat(added.igdbId()).isEqualTo("77");
        assertThat(added.proposedDtddId()).isEqualTo(5L);
        assertThat(added.reason()).isEqualTo("wrong game");
        assertThat(added.proposer().twitchUsername()).isEqualTo("alice");
        assertThat(added.status()).isEqualTo("PENDING");
    }
}
