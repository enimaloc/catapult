package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link TwitchService} that holds back every write to the channel of a member in dry run: nothing is sent, the write
 * is noted ({@link DryRunGate#note}) and answered as done ({@link ChannelUpdateOutcome#DRY_RUN}), so that the rest of
 * Catapult goes on as usual. Reads, and every call for a member not in dry run, go to {@code delegate} unchanged; so
 * does a write the delegate would skip or refuse anyway (bot disabled, account inactive, standby site), with nothing
 * noted. Wrapped around each {@link TwitchService} bean by {@link DryRunTwitchWrapping}.
 *
 * <p>Until who is in dry run is known ({@link DryRunGate#holdsUntilLoaded}), every member's write is held too, and
 * answered as not made: {@link ChannelUpdateOutcome#FAILED}, or an exception for the extensions, so that nothing takes
 * the channel as changed.</p>
 */
public class DryRunTwitchService implements TwitchService {

    private static final String CHANGED = "[dry run] would have changed the channel";

    private final TwitchService delegate;
    private final Supplier<DryRunGate> gate;

    public DryRunTwitchService(TwitchService delegate, Supplier<DryRunGate> gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "updateChannelReporting")) {
            return ChannelUpdateOutcome.FAILED;
        }
        if (!dryRun.holdsChannel(user)) {
            return delegate.updateChannelReporting(user, binding);
        }
        if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            // the real client sends nothing either
            return ChannelUpdateOutcome.SKIPPED;
        }
        List<String> fields = new ArrayList<>();
        category(binding.getTwitchGameId(), binding.getTwitchGameName()).ifPresent(fields::add);
        dryRun.plannedTitle(user, binding).ifPresent(title -> fields.add("title \"" + title + "\""));
        dryRun.note(user, "updateChannelReporting", changed(fields));
        dryRun.announceCategory(user, binding.getTwitchGameId(), binding.getTwitchGameName());
        return ChannelUpdateOutcome.DRY_RUN;
    }

    @Override
    public ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "setCategory")) {
            return ChannelUpdateOutcome.FAILED;
        }
        if (!dryRun.holdsChannel(user)) {
            return delegate.setCategory(user, twitchGameId, twitchGameName);
        }
        dryRun.note(user, "setCategory", changed(category(twitchGameId, twitchGameName).stream().toList()));
        dryRun.announceCategory(user, twitchGameId, twitchGameName);
        return ChannelUpdateOutcome.DRY_RUN;
    }

    @Override
    public ChannelUpdateOutcome resetToDefault(UserAccount user) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "resetToDefault")) {
            return ChannelUpdateOutcome.FAILED;
        }
        if (!dryRun.holdsChannel(user)) {
            return delegate.resetToDefault(user);
        }
        Optional<DryRunGate.Category> noGame = dryRun.noGameCategory(user);
        if (noGame.isEmpty()) {
            // no default category: the real client sends nothing either
            return ChannelUpdateOutcome.SKIPPED;
        }
        dryRun.note(user, "resetToDefault", "[dry run] would have reset the category");
        dryRun.announceCategory(user, noGame.get().id(), noGame.get().name());
        return ChannelUpdateOutcome.DRY_RUN;
    }

    /** Held: answers {@code placement} as the channel's placement, nothing having been read back from Twitch. */
    @Override
    public TwitchExtensionPlacement setActiveExtensions(UserAccount user, TwitchExtensionPlacement placement) {
        DryRunGate dryRun = gate.get();
        if (dryRun.holdsUntilLoaded(user, "setActiveExtensions")) {
            throw notLoaded();
        }
        if (!dryRun.holdsChannel(user)) {
            return delegate.setActiveExtensions(user, placement);
        }
        dryRun.note(user, "setActiveExtensions", "[dry run] would have set the extensions");
        return placement;
    }

    @Override
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        return delegate.findCategoryIdByName(user, gameName);
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return delegate.searchCategories(user, query);
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        return delegate.getModeratedChannelIds(viewer);
    }

    @Override
    public List<TwitchExtension> getUserExtensions(UserAccount user) {
        return delegate.getUserExtensions(user);
    }

    @Override
    public TwitchExtensionPlacement getActiveExtensions(UserAccount user) {
        return delegate.getActiveExtensions(user);
    }

    private static IllegalStateException notLoaded() {
        return new IllegalStateException("Twitch write held: dry-run state not loaded yet");
    }

    /** {@code [dry run] would have changed the channel: <fields>}, without the colon when no field is known. */
    private static String changed(List<String> fields) {
        return fields.isEmpty() ? CHANGED : CHANGED + ": " + String.join(", ", fields);
    }

    /** {@code category <name>} (its id when it has no name), empty without either. */
    private static Optional<String> category(String id, String name) {
        String shown = name != null && !name.isBlank() ? name : id;
        return shown == null || shown.isBlank() ? Optional.empty() : Optional.of("category " + shown);
    }
}
