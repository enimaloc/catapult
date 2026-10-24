package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatDispatch;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Typed convenience layer over {@link RedisEventPublisher} for per-channel domain events, whose wire
 * contract is {@link ChannelEvents}: each method names its event and builds its payload, so callers do
 * not have to remember either.
 *
 * <p>Channel: {@code catapult:events:channel:<ownerId>}, relayed by every catapult-web instance to the
 * dashboards following that channel. Every payload carries {@code channelId} and {@code channelLogin}
 * (the owner's Twitch login, lower case, read here): catapult-web keys its streams by login. An owner
 * that no longer exists publishes nothing.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelEventPublisher {

    private final RedisEventPublisher redisPublisher;
    private final UserAccountRepository userAccountRepository;
    private final GameBindingRepository gameBindingRepository;

    public void botToggled(UUID channelOwnerId, boolean enabled) {
        publish(channelOwnerId, ChannelEvents.BOT_TOGGLED, (id, login) -> new ChannelEvents.BotToggled(id, login, enabled));
    }

    public void streamStateChanged(UUID channelOwnerId, boolean isLive) {
        publish(channelOwnerId, ChannelEvents.STREAM_STATE_CHANGED,
                (id, login) -> new ChannelEvents.StreamStateChanged(id, login, isLive));
    }

    /** {@code game} now detected, with the channel's binding for it when it has one already; none clears it. */
    public void gameDetected(UUID channelOwnerId, DetectedGame game) {
        if (game == null) {
            gameCleared(channelOwnerId);
            return;
        }
        owner(channelOwnerId).ifPresent(owner -> {
            String bindingId = game.getSourceId() == null || game.getSourceType() == null ? null : gameBindingRepository
                    .findByUserAndSourceIdAndSourceType(owner, game.getSourceId(), game.getSourceType())
                    .map(binding -> binding.getId().toString())
                    .orElse(null);
            publish(owner, ChannelEvents.GAME_DETECTED, (id, login) -> new ChannelEvents.GameDetected(id, login, bindingId,
                    game.getSourceName(), game.getSourceType() == null ? null : game.getSourceType().name()));
        });
    }

    public void gameCleared(UUID channelOwnerId) {
        publish(channelOwnerId, ChannelEvents.GAME_CLEARED, ChannelEvents.GameCleared::new);
    }

    /** {@code binding}'s whole state, as the dashboard lists it. */
    public void bindingUpserted(UUID channelOwnerId, GameBinding binding) {
        BindingDto dto = toDto(binding);
        publish(channelOwnerId, ChannelEvents.BINDING_UPSERTED, (id, login) -> new ChannelEvents.BindingUpserted(id, login, dto));
    }

    public void bindingDeleted(UUID channelOwnerId, UUID bindingId) {
        publish(channelOwnerId, ChannelEvents.BINDING_DELETED,
                (id, login) -> new ChannelEvents.BindingDeleted(id, login, bindingId.toString()));
    }

    public void settingsUpdated(UUID channelOwnerId, UserSettings settings) {
        ChannelEvents.Settings dto = toDto(settings);
        publish(channelOwnerId, ChannelEvents.SETTINGS_UPDATED, (id, login) -> new ChannelEvents.SettingsUpdated(id, login, dto));
    }

    public void steamProfileChanged(UUID channelOwnerId, SteamProfileDto profile) {
        ChannelEvents.SteamProfile dto = toDto(profile);
        publish(channelOwnerId, ChannelEvents.STEAM_PROFILE_CHANGED,
                (id, login) -> new ChannelEvents.SteamProfileChanged(id, login, dto));
    }

    public void dtddMappingChanged(UUID channelOwnerId) {
        publish(channelOwnerId, ChannelEvents.DTDD_MAPPING_CHANGED, ChannelEvents.DtddMappingChanged::new);
    }

    public void connectionChanged(UUID channelOwnerId, ProviderConnectionsDto provider) {
        ChannelEvents.SteamProfile profile = provider.profile() == null ? null : toDto(provider.profile());
        publish(channelOwnerId, ChannelEvents.CONNECTION_CHANGED, (id, login) -> new ChannelEvents.ConnectionChanged(id, login,
                provider.provider(), provider.connected(), profile));
    }

    /** The channel's Minecraft link is now {@code status} ({@code NONE} once gone), for {@code minecraftName}. */
    public void minecraftLinkChanged(UUID channelOwnerId, String status, String minecraftName) {
        publish(channelOwnerId, ChannelEvents.MINECRAFT_LINK_CHANGED,
                (id, login) -> new ChannelEvents.MinecraftLinkChanged(id, login, status, minecraftName));
    }

    public void twitchatDispatch(UUID channelOwnerId, TwitchatDispatch dispatch) {
        redisPublisher.publishTwitchat(channelOwnerId, "twitchat.dispatch", dispatch);
    }

    public void twitchatWidgetSettingsUpdated(UUID channelOwnerId, TwitchatWidgetConfig config) {
        redisPublisher.publishTwitchat(channelOwnerId, "twitchat.widget.settings.updated", config);
    }

    private void publish(UUID channelOwnerId, String name, BiFunction<String, String, Object> payload) {
        owner(channelOwnerId).ifPresent(owner -> publish(owner, name, payload));
    }

    private void publish(UserAccount owner, String name, BiFunction<String, String, Object> payload) {
        String login = owner.getTwitchUsername() == null ? null : owner.getTwitchUsername().toLowerCase(Locale.ROOT);
        redisPublisher.publishChannel(owner.getId(), name, payload.apply(owner.getId().toString(), login));
    }

    private Optional<UserAccount> owner(UUID channelOwnerId) {
        Optional<UserAccount> owner = userAccountRepository.findById(channelOwnerId);
        if (owner.isEmpty()) log.debug("No channel event for {}: no such account", channelOwnerId);
        return owner;
    }

    static BindingDto toDto(GameBinding binding) {
        return new BindingDto(binding.getId().toString(), binding.getStatus().name(), binding.getSourceType().name(),
                binding.getSourceName(), binding.getTwitchGameId(), binding.getTwitchGameName(), binding.isIgnored(),
                binding.isCclEnabled(), Set.copyOf(binding.getCcls()), binding.isTwEnabled(), binding.isTwOverride(),
                Set.copyOf(binding.getTws()));
    }

    static ChannelEvents.Settings toDto(UserSettings s) {
        return new ChannelEvents.Settings(
                new ChannelEvents.Settings.Ccl(s.isCclFeatureEnabled(), Set.copyOf(s.getBlockedCcls())),
                new ChannelEvents.Settings.Tw(s.isTwFeatureEnabled(), Set.copyOf(s.getBlockedTws())),
                new ChannelEvents.Settings.NoGame(s.getNoGameTwitchGameId(), s.getNoGameTwitchGameName(),
                        Set.copyOf(s.getNoGameCcls()), s.isApplyDefaultOnStreamStart(), s.isApplyDefaultOnNoGame(),
                        s.isApplyDefaultOnStreamEnd()),
                new ChannelEvents.Settings.IncompleteFallback(s.getIncompleteFallbackTwitchGameId(),
                        s.getIncompleteFallbackTwitchGameName(), Set.copyOf(s.getIncompleteFallbackCcls())),
                new ChannelEvents.Settings.Raid(s.getRaidRestoreGameMinutes()));
    }

    static ChannelEvents.SteamProfile toDto(SteamProfileDto p) {
        return new ChannelEvents.SteamProfile(p.hasSteam(), p.profilePrivate(), p.offlineMode(), p.rateLimited(),
                p.ttlMinutes(), p.hasPersonalToken(), p.tokenShared());
    }
}
