package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryApplierTest {

    @Mock private TwitchService twitchService;
    @Mock private StreamStateService streamStateService;

    private CategoryApplier applier;
    private UserAccount user;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        applier = new CategoryApplier(twitchService, streamStateService);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = new GameBinding();
        binding.setTwitchGameId("29595");
    }

    private CategoryOutcome update(ChannelUpdateOutcome twitch) {
        when(twitchService.updateChannelReporting(user, binding)).thenReturn(twitch);
        CategoryOutcome outcome = applier.apply(user, CategoryDecision.update(CategoryRule.GAME_BINDING, binding));
        verify(twitchService).updateChannelReporting(user, binding);
        verifyNoMoreInteractions(twitchService);
        verifyNoInteractions(streamStateService);
        return outcome;
    }

    @Test
    void updateChannel_applied() {
        assertThat(update(ChannelUpdateOutcome.APPLIED)).isEqualTo(CategoryOutcome.applied(CategoryRule.GAME_BINDING));
    }

    @Test
    void updateChannel_skipped() {
        assertThat(update(ChannelUpdateOutcome.SKIPPED))
                .isEqualTo(CategoryOutcome.skipped(CategoryRule.GAME_BINDING, Reason.TWITCH_SKIPPED));
    }

    @Test
    void updateChannel_failed() {
        assertThat(update(ChannelUpdateOutcome.FAILED))
                .isEqualTo(CategoryOutcome.failed(CategoryRule.GAME_BINDING, Reason.TWITCH_FAILED));
    }

    @Test
    void updateChannel_null_isFailed() {
        assertThat(update(null)).isEqualTo(CategoryOutcome.failed(CategoryRule.GAME_BINDING, Reason.TWITCH_FAILED));
    }

    @Test
    void setCategory_callsSetCategory() {
        when(twitchService.setCategory(user, "509658", "Just Chatting")).thenReturn(ChannelUpdateOutcome.APPLIED);

        CategoryOutcome outcome = applier.apply(user,
                CategoryDecision.setCategory(CategoryRule.MANUAL_CHOICE, "509658", "Just Chatting"));

        assertThat(outcome).isEqualTo(CategoryOutcome.applied(CategoryRule.MANUAL_CHOICE));
        verify(twitchService).setCategory(user, "509658", "Just Chatting");
        verifyNoMoreInteractions(twitchService);
        verifyNoInteractions(streamStateService);
    }

    @Test
    void resetToDefault_callsResetToDefault() {
        when(twitchService.resetToDefault(user)).thenReturn(ChannelUpdateOutcome.SKIPPED);

        CategoryOutcome outcome = applier.apply(user, CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY));

        assertThat(outcome).isEqualTo(CategoryOutcome.skipped(CategoryRule.DEFAULT_CATEGORY, Reason.TWITCH_SKIPPED));
        verify(twitchService).resetToDefault(user);
        verifyNoMoreInteractions(twitchService);
        verifyNoInteractions(streamStateService);
    }

    @Test
    void storePending_storesItWithoutTwitch() {
        CategoryOutcome outcome = applier.apply(user, CategoryDecision.storePending(CategoryRule.GAME_BINDING, binding));

        assertThat(outcome).isEqualTo(CategoryOutcome.skipped(CategoryRule.GAME_BINDING, Reason.STORED_PENDING));
        verify(streamStateService).storePending(user, binding);
        verifyNoMoreInteractions(streamStateService);
        verifyNoInteractions(twitchService);
    }

    @Test
    void noneCallsNothing() {
        CategoryOutcome outcome = applier.apply(user,
                CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD));

        assertThat(outcome).isEqualTo(CategoryOutcome.skipped(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD));
        verifyNoInteractions(twitchService, streamStateService);
    }

    @Test
    void aThrowIsCarried() {
        IllegalStateException boom = new IllegalStateException("Twitch down");
        when(twitchService.updateChannelReporting(user, binding)).thenThrow(boom);

        CategoryOutcome outcome = applier.apply(user, CategoryDecision.update(CategoryRule.GAME_BINDING, binding));

        assertThat(outcome.error()).isSameAs(boom);
        assertThat(outcome.status()).isEqualTo(ChannelUpdateOutcome.FAILED);
        assertThat(outcome.reason()).isEqualTo(Reason.ERROR);
        assertThat(outcome.rule()).isEqualTo(CategoryRule.GAME_BINDING);
        assertThatThrownBy(outcome::orThrow).isSameAs(boom);
    }

    @Test
    void orThrow_withoutError_returnsTheOutcome() {
        CategoryOutcome outcome = CategoryOutcome.applied(CategoryRule.GAME_BINDING);

        assertThat(outcome.orThrow()).isSameAs(outcome);
    }
}
