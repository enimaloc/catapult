package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;

import java.util.List;
import java.util.Optional;

public interface TwitchService {
    void updateChannel(UserAccount user, GameBinding binding);

    /**
     * {@link #updateChannel}, telling what happened: applied when Twitch accepted the change, skipped when there was
     * nothing to send, failed when the update was refused or could not be sent. Errors are still logged, not thrown.
     */
    ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding);
    void setCategory(UserAccount user, String twitchGameId, String twitchGameName);
    Optional<String> findCategoryIdByName(UserAccount user, String gameName);
    List<TwitchCategory> searchCategories(UserAccount user, String query);
    void resetToDefault(UserAccount user);
    List<String> getModeratedChannelIds(UserAccount viewer);
}
