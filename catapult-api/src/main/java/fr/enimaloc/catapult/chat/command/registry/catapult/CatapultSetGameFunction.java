package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pushes a one-shot Twitch category change — the {@link GameBinding} built here is never
 * persisted, so it's a temporary override: the next real auto-detected game event overwrites it
 * through the normal detection pipeline. There is no expiry/duration parameter and no scheduled
 * revert; "temporary" means "until the next real detection", not "until a timer". This is what
 * the {@code setgame} preset's default template ({@code chat.preset.setgame.template}) calls to
 * implement {@code !setgame}, now a fully data-driven command like any other (no more
 * Java-hardcoded {@code SetGameCommand} bean).
 *
 * <p>Always resolves a Twitch category id before calling {@link TwitchService#updateChannel}
 * (skipping this — sending {@code game_id: null} — is a silent no-op on Twitch's side): {@code
 * igdbId}, when given, maps to a Twitch id via {@link IgdbService#findTwitchGameId}; either way
 * (or as a fallback when that mapping is missing) it falls back to a live Twitch category name
 * search ({@link TwitchService#findCategoryIdByName}) — the same two-step resolution {@link
 * fr.enimaloc.catapult.service.BindingService#updateWithIgdbResolution} already uses for
 * auto-detected games, which a MANUAL binding built this way never went through before.
 *
 * <p>The optional {@code igdbId} also lets the streamer pin the exact IGDB game instead of
 * relying on a name search for enrichment — {@code catapult#getGame().sourceId} then feeds
 * {@code igdb#get*(id)} sub-data functions directly (same "sourceId feeds a same-source lookup"
 * convention {@link CatapultGetGameFunction} already documents for Steam appIds). Without an
 * {@code igdbId}, {@link GameStateService}'s in-memory cache isn't touched here at all — it's
 * only refreshed once the Twitch category-change webhook round-trips back through the normal
 * detection pipeline, so a chained {@code catapult#getGame()} right after this call would still
 * see the previous game.
 */
@Component
@RequiredArgsConstructor
public class CatapultSetGameFunction implements ServiceFunction {

    private final TwitchService twitchService;
    private final GameStateService gameStateService;
    private final IgdbService igdbService;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "setGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("name", "igdbId");
    }

    @Override
    public List<String> optionalParameterNames() {
        return List.of("igdbId");
    }

    @Override
    public boolean isAction() {
        return true;
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String gameName = String.valueOf(args[0]);
        String igdbId = ServiceFunction.optionalArg(args, 1, null);

        GameBinding binding = new GameBinding();
        binding.setUser(user);
        binding.setSourceType(GameBinding.SourceType.MANUAL);
        binding.setSourceName(gameName);
        binding.setTwitchGameName(gameName);
        binding.setStatus(GameBinding.Status.MANUAL);
        binding.setCcls(Set.of());
        if (igdbId != null && !igdbId.isBlank()) {
            binding.setSourceId(igdbId);
        }
        binding.setTwitchGameId(resolveTwitchGameId(user, gameName, igdbId).orElse(null));
        twitchService.updateChannel(user, binding);

        if (igdbId != null && !igdbId.isBlank()) {
            gameStateService.updateState(user,
                new DetectedGame(igdbId, GameBinding.SourceType.MANUAL, gameName));
        }

        return "";
    }

    private Optional<String> resolveTwitchGameId(UserAccount user, String gameName, String igdbId) {
        if (igdbId != null && !igdbId.isBlank()) {
            Optional<String> viaIgdb = igdbService.findTwitchGameId(igdbId);
            if (viaIgdb.isPresent()) {
                return viaIgdb;
            }
        }
        return twitchService.findCategoryIdByName(user, gameName);
    }
}
