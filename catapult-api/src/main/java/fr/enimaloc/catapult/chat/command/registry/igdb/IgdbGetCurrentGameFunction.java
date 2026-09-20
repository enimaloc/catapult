package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.chat.command.registry.DtoMapper;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * IGDB-enriched details for the streamer's currently detected game, regardless of which
 * provider (Steam/Xbox/BattleNet/Minecraft/manual) detected it — the same enrichment {@code
 * game#*} context paths already surface, exposed as a proper service-call object instead.
 */
@Component
@RequiredArgsConstructor
public class IgdbGetCurrentGameFunction implements ServiceFunction {

    /** All-{@code ""} fields when no game is currently detected or IGDB has no data for it. */
    public record Result(String name, String summary, String steamReleaseDate, String storeUrl,
                          String storeSteamUrl, String storeXboxUrl, String storeBattlenetUrl,
                          String storeOfficialUrl, String igdbUrl, String ageRating, String rating,
                          String criticRating, String platforms) {}

    private static final Result EMPTY =
        new Result("", "", "", "", "", "", "", "", "", "", "", "", "");

    private final GameContextService gameContextService;

    @Override
    public String namespace() {
        return "igdb";
    }

    @Override
    public String name() {
        return "getCurrentGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of();
    }

    @Override
    public List<String> returnKeys() {
        return DtoMapper.keys(Result.class);
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        Result result = gameContextService.get(user).map(IgdbGetCurrentGameFunction::toResult).orElse(EMPTY);
        return DtoMapper.toMap(result);
    }

    private static Result toResult(GameContext ctx) {
        return new Result(
            orEmpty(ctx.name()),
            orEmpty(ctx.summary()),
            ctx.releaseDate() == null ? "" : DateTimeFormatter.ISO_LOCAL_DATE.format(ctx.releaseDate()),
            orEmpty(ctx.activeStoreUrl()),
            storeUrl(ctx, "steam"),
            storeUrl(ctx, "xbox"),
            storeUrl(ctx, "battlenet"),
            storeUrl(ctx, "official"),
            ctx.igdbSlug() == null ? "" : "https://www.igdb.com/games/" + ctx.igdbSlug(),
            orEmpty(ctx.ageRating()),
            ctx.rating() == null ? "" : String.valueOf(Math.round(ctx.rating())),
            ctx.criticRating() == null ? "" : String.valueOf(Math.round(ctx.criticRating())),
            ctx.platforms() == null || ctx.platforms().isEmpty() ? "" : String.join(", ", ctx.platforms()));
    }

    private static String storeUrl(GameContext ctx, String key) {
        return ctx.stores() == null ? "" : orEmpty(ctx.stores().get(key));
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
