package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only Twitch of {@link ChannelFixture}: records every category write (what was sent, under which debug subject)
 * instead of calling Twitch, and appends it to the fixture's journal. Sends nothing anywhere and checks nothing: a
 * binding the real Twitch layer would skip (ignored, INCOMPLETE, bot off) is recorded as sent.
 */
public class RecordingTwitch implements TwitchService {

    public enum Kind { UPDATE, SET_CATEGORY, RESET }

    /**
     * One category write. {@code persisted}: the binding sent has an id (a saved binding, not a transient one), which
     * is what decides whether Twitch applies the CCL blocklist; {@code cclEnabled}: whether the binding sends its
     * CCLs at all.
     */
    public record Write(Kind kind, String gameId, String gameName, String sourceName, GameBinding.Status status,
                        Set<String> ccls, boolean cclEnabled, boolean ignored, boolean persisted,
                        Optional<UUID> subject) {}

    private final List<String> journal;
    private final List<Write> writes = new ArrayList<>();
    private final Map<String, String> categories = new HashMap<>();
    private ChannelUpdateOutcome answer = ChannelUpdateOutcome.APPLIED;
    private RuntimeException next;
    private int thrown;
    private Consumer<Write> onWrite = w -> { };

    RecordingTwitch(List<String> journal) {
        this.journal = journal;
    }

    public List<Write> writes() {
        return List.copyOf(writes);
    }

    /** What the update calls answer from now on ({@code APPLIED} until told otherwise). */
    public void answer(ChannelUpdateOutcome outcome) {
        this.answer = outcome;
    }

    /** The next write throws {@code e} instead of being recorded (it still goes through {@link #onWrite}). */
    public void throwNext(RuntimeException e) {
        this.next = e;
    }

    /** How many writes have thrown so far (see {@link #throwNext}). */
    public int thrown() {
        return thrown;
    }

    /** {@link #findCategoryIdByName} answers {@code id} for {@code name}. */
    public void category(String name, String id) {
        categories.put(name, id);
    }

    /**
     * Called at each write attempt, before it is recorded or thrown: to observe the state at the moment of the call.
     */
    public void onWrite(Consumer<Write> hook) {
        this.onWrite = hook;
    }

    @Override
    public ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding) {
        update(binding);
        return answer;
    }

    @Override
    public ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName) {
        record(new Write(Kind.SET_CATEGORY, twitchGameId, twitchGameName, null, null, Set.of(), false, false, false,
                DebugContext.subject()), "twitch:set " + twitchGameId);
        return answer;
    }

    @Override
    public ChannelUpdateOutcome resetToDefault(UserAccount user) {
        record(new Write(Kind.RESET, null, null, null, null, Set.of(), false, false, false,
                DebugContext.subject()),
                "twitch:reset");
        return answer;
    }

    @Override
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        return Optional.ofNullable(categories.get(gameName));
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return List.of();
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        return List.of();
    }

    @Override
    public List<TwitchExtension> getUserExtensions(UserAccount user) {
        return List.of();
    }

    @Override
    public TwitchExtensionPlacement getActiveExtensions(UserAccount user) {
        return TwitchExtensionPlacement.empty();
    }

    @Override
    public TwitchExtensionPlacement setActiveExtensions(UserAccount user, TwitchExtensionPlacement placement) {
        return TwitchExtensionPlacement.empty();
    }

    private void update(GameBinding binding) {
        record(new Write(Kind.UPDATE, binding.getTwitchGameId(), binding.getTwitchGameName(),
                binding.getSourceName(), binding.getStatus(), Set.copyOf(binding.getCcls()), binding.isCclEnabled(),
                binding.isIgnored(),
                binding.getId() != null, DebugContext.subject()),
                "twitch:update " + binding.getTwitchGameId() + " " + binding.getSourceName());
    }

    private void record(Write write, String entry) {
        onWrite.accept(write);
        if (next != null) {
            RuntimeException e = next;
            next = null;
            thrown++;
            throw e;
        }
        writes.add(write);
        journal.add(entry);
    }
}
