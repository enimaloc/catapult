package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;

import java.util.List;
import java.util.Optional;

public interface TwitchService {
    /**
     * Sets the channel's category from a binding, telling what happened: applied when Twitch accepted the change, skipped when there was
     * nothing to send, failed when the update was refused or could not be sent. Errors are still logged, not thrown.
     */
    ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding);
    /** Sends a bare Twitch category: applied once patched, skipped when the bot is off, failed without a token. */
    ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName);
    Optional<String> findCategoryIdByName(UserAccount user, String gameName);
    List<TwitchCategory> searchCategories(UserAccount user, String query);
    /** Sends the no-game default category, telling what happened. */
    ChannelUpdateOutcome resetToDefault(UserAccount user);
    List<String> getModeratedChannelIds(UserAccount viewer);
}
