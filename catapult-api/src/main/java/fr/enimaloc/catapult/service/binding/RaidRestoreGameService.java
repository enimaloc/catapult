package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryDecision;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.RaidedOut;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * A streamer who closes their game gets the "no game" category; raiding out minutes later would then
 * leave from it. With the channel's {@link UserSettings#getRaidRestoreGameMinutes() delay} set (and the
 * {@value RaidRestoreGateService#EXPERIMENT_KEY} gate on), an outgoing raid puts the game detected within
 * that delay back (unless the channel is already on it), and the "no game" fallback then holds off until the
 * stream ends or a new game is detected ({@link RaidRestoreHold}), so a later detection doesn't undo it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaidRestoreGameService {

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 120;

    private final CategoryChain categoryChain;
    private final CategoryApplier categoryApplier;
    private final RaidRestoreHold raidRestoreHold;

    @EventListener
    public void onRaidedOut(ChannelRaidedOutEvent event) {
        DebugContext.runAs(event.getUser().getId(), () -> restore(event));
    }

    private void restore(ChannelRaidedOutEvent event) {
        UserAccount user = event.getUser();
        CategoryDecision decision = categoryChain.decide(user, new RaidedOut(event.getCurrentCategoryId()));
        if (decision.rule() == CategoryRule.RAID_RESTORE
                && (decision.reason() == CategoryOutcome.Reason.NONE
                || decision.reason() == CategoryOutcome.Reason.ALREADY_ON_CATEGORY)) {
            // Held first, so a detection tick running meanwhile can't put the "no game" category back after us;
            // also when already on the game, which may still be closed before the stream ends.
            raidRestoreHold.hold(user);
        }
        if (decision.action() == CategoryDecision.Action.UPDATE_CHANNEL) {
            log.info("Raid by user {}: putting {} back as the category", user.getId(),
                    decision.binding().getTwitchGameName());
        }
        // Applying records Catapult's own change, so the channel.update isn't taken for a manual one
        CategoryOutcome outcome = categoryApplier.apply(user, decision);
        if (outcome.error() != null) {
            raidRestoreHold.release(user);
            log.warn("Raid by user {}: could not put {} back: {}", user.getId(),
                    decision.binding().getTwitchGameName(), outcome.error().getMessage());
        }
    }
}
