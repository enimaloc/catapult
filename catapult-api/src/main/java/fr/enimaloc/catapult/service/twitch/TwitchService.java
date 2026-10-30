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
    /**
     * Sends a bare Twitch category: applied once patched, skipped when the bot is off, failed without a token. An
     * expired token is refreshed and the PATCH retried once (the bot paused when that fails); any HTTP error that
     * remains is thrown.
     */
    ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName);
    Optional<String> findCategoryIdByName(UserAccount user, String gameName);
    List<TwitchCategory> searchCategories(UserAccount user, String query);
    /** Sends the no-game default category, telling what happened. */
    ChannelUpdateOutcome resetToDefault(UserAccount user);
    List<String> getModeratedChannelIds(UserAccount viewer);
    /** The extensions the user installed on their channel, empty without a token or when Twitch fails. */
    List<TwitchExtension> getUserExtensions(UserAccount user);
    /** The extensions active on the user's channel, {@link TwitchExtensionPlacement#empty()} when it cannot be read. */
    TwitchExtensionPlacement getActiveExtensions(UserAccount user);
    /**
     * Sends {@code placement}, only its non-null slots changing (see {@link TwitchExtensionPlacement#update()}), and
     * answers the channel's placement as Twitch has it afterwards. Nothing is swallowed, as with {@link #setCategory}:
     * a disabled bot, an inactive account or a missing token throw {@link IllegalStateException}, an HTTP error is
     * thrown as is.
     */
    TwitchExtensionPlacement setActiveExtensions(UserAccount user, TwitchExtensionPlacement placement);
}
