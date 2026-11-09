package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.RaidSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameSettingsSaved;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.function.Consumer;

/**
 * What a channel's dashboard saves under its settings: each write is saved, then (for the no-game fallback) applied to
 * the category, then published to the channel's dashboards. Not transactional, as the handlers it serves were not.
 */
@Service
@RequiredArgsConstructor
public class ChannelSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final CategoryChain categoryChain;
    private final RaidRestoreGateService raidRestoreGate;
    private final ChannelEventPublisher channelEventPublisher;
    private final ExtensionLayoutApplier extensionLayoutApplier;

    public void saveCcl(UserAccount channel, CclSettingsRequest body) {
        UserSettings settings = settings(channel, s -> {
            s.setCclFeatureEnabled(body.cclEnabled());
            replace(s.getBlockedCcls(), body.blockedCcls());
        });
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    public void saveTws(UserAccount channel, TwSettingsRequest body) {
        UserSettings settings = settings(channel, s -> {
            s.setTwFeatureEnabled(body.enabled());
            replace(s.getBlockedTws(), body.blockedTws());
        });
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    public void saveNoGame(UserAccount channel, NoGameSettingsRequest body) {
        UserSettings settings = settings(channel, s -> {
            s.setNoGameTwitchGameId(body.twitchGameId());
            s.setNoGameTwitchGameName(body.twitchGameName());
            replace(s.getNoGameCcls(), body.ccls());
            s.setApplyDefaultOnStreamStart(body.applyOnStreamStart());
            s.setApplyDefaultOnNoGame(body.applyOnNoGame());
            s.setApplyDefaultOnStreamEnd(body.applyOnStreamEnd());
        });
        // The new default category applies immediately when the "no game" fallback would apply now (nothing
        // detected, no custom binding, live, enabled, no raid hold); otherwise the settings are only saved
        // Recorded in the channel's debug log, whoever saves the settings (a moderator, an admin)
        DebugContext.runAs(channel.getId(),
                () -> categoryChain.apply(channel, new NoGameSettingsSaved(settings.isApplyDefaultOnNoGame())).orThrow());
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    public void saveIncompleteFallback(UserAccount channel, IncompleteFallbackRequest body) {
        UserSettings settings = settings(channel, s -> {
            s.setIncompleteFallbackTwitchGameId(body.twitchGameId());
            s.setIncompleteFallbackTwitchGameName(body.twitchGameName());
            replace(s.getIncompleteFallbackCcls(), body.ccls());
        });
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    /** Replaces the no-game fallback's extension layout and pushes it when that fallback is in place. */
    public void saveNoGameExtensions(UserAccount channel, Set<ExtensionSlotSetting> slots) {
        UserSettings settings = settings(channel, s -> {
            s.getNoGameExtensionSlots().clear();
            s.getNoGameExtensionSlots().addAll(slots);
        });
        extensionLayoutApplier.applyIfCurrent(channel, ExtensionLayoutApplier.NO_GAME_KEY, slots);
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    /** Replaces the incomplete fallback's extension layout and pushes it when that fallback is in place. */
    public void saveIncompleteFallbackExtensions(UserAccount channel, Set<ExtensionSlotSetting> slots) {
        UserSettings settings = settings(channel, s -> {
            s.getIncompleteFallbackExtensionSlots().clear();
            s.getIncompleteFallbackExtensionSlots().addAll(slots);
        });
        extensionLayoutApplier.applyIfCurrent(channel, ExtensionLayoutApplier.INCOMPLETE_KEY, slots);
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    /** 0 or null turns it off; refused (403) while the channel isn't in the {@value RaidRestoreGateService#EXPERIMENT_KEY} experiment. */
    public void saveRaid(UserAccount channel, RaidSettingsRequest body) {
        if (!raidRestoreGate.isAvailableFor(channel)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Raid settings not available for this channel");
        }
        Integer minutes = body.restoreGameMinutes();
        if (minutes != null && minutes != 0
                && (minutes < RaidRestoreGameService.MIN_MINUTES || minutes > RaidRestoreGameService.MAX_MINUTES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "restoreGameMinutes must be 1..120, or 0/null for off");
        }
        UserSettings settings = settings(channel,
                s -> s.setRaidRestoreGameMinutes(minutes == null || minutes == 0 ? null : minutes));
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    /** Loads the channel's settings (new ones when it has none), applies {@code changes} and saves them. */
    private UserSettings settings(UserAccount channel, Consumer<UserSettings> changes) {
        UserSettings settings = userSettingsRepository.findById(channel.getId()).orElseGet(() -> {
            UserSettings created = new UserSettings();
            created.setUser(channel);
            return created;
        });
        changes.accept(settings);
        userSettingsRepository.save(settings);
        return settings;
    }

    /** Replaces {@code target}'s content with {@code values} (none when null). */
    private static void replace(Set<String> target, Set<String> values) {
        target.clear();
        if (values != null) target.addAll(values);
    }
}
