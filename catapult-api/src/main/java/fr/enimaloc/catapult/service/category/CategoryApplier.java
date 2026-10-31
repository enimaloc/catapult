package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Carries out a {@link CategoryDecision}: exactly one {@link TwitchService} call (or the pending binding stored),
 * whose answer becomes a {@link CategoryOutcome}. An exception from the Twitch layer is carried in the outcome, never
 * thrown: the caller decides ({@link CategoryOutcome#orThrow()}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryApplier {

    private final TwitchService twitchService;
    private final StreamStateService streamStateService;

    public CategoryOutcome apply(UserAccount user, CategoryDecision decision) {
        CategoryRule rule = decision.rule();
        try {
            return switch (decision.action()) {
                case UPDATE_CHANNEL -> fromTwitch(rule, twitchService.updateChannelReporting(user, decision.binding()));
                case SET_CATEGORY -> fromTwitch(rule,
                        twitchService.setCategory(user, decision.twitchGameId(), decision.twitchGameName()));
                case RESET_TO_DEFAULT -> fromTwitch(rule, twitchService.resetToDefault(user));
                case STORE_PENDING -> {
                    streamStateService.storePending(user, decision.binding());
                    yield CategoryOutcome.skipped(rule, Reason.STORED_PENDING);
                }
                case NONE -> CategoryOutcome.skipped(rule, decision.reason());
            };
        } catch (RuntimeException e) {
            log.debug("Category {} for user {} threw: {}", rule, user.getId(), e.toString());
            return CategoryOutcome.error(rule, e);
        }
    }

    private static CategoryOutcome fromTwitch(CategoryRule rule, ChannelUpdateOutcome status) {
        if (status == ChannelUpdateOutcome.APPLIED) return CategoryOutcome.applied(rule);
        if (status == ChannelUpdateOutcome.SKIPPED) return CategoryOutcome.skipped(rule, Reason.TWITCH_SKIPPED);
        // FAILED, or null from a mock
        return CategoryOutcome.failed(rule, Reason.TWITCH_FAILED);
    }
}
