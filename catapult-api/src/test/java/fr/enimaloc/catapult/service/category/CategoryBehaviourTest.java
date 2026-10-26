package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.binding.CustomBindingService.UpdateResult;
import fr.enimaloc.catapult.service.category.RecordingTwitch.Kind;
import fr.enimaloc.catapult.service.category.RecordingTwitch.Write;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor.Result;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * How every trigger sets a channel's Twitch category today, pinned through one {@link ChannelFixture} before the
 * rules move. A trigger is driven the way its entry point drives it; the assertions read only what Twitch was sent
 * ({@link RecordingTwitch}), the dashboard events in between, and the channel's pending / hold / activation state.
 */
class CategoryBehaviourTest {

    private ChannelFixture f;
    /** The game played in most tests: STEAM AUTO "Minecraft", CCL {@code ViolentGraphic}. */
    private GameBinding g1;
    /** An interruptible custom binding, CCL {@code SexualThemes}. */
    private CustomBinding art;

    @BeforeEach
    void setUp() {
        f = new ChannelFixture();
        g1 = f.binding("g1", GameBinding.Status.AUTO, "27471", "Minecraft");
        g1.getCcls().add("ViolentGraphic");
        art = f.custom("Art", "509660", "Art", false);
        art.getCcls().add("SexualThemes");
    }

    @AfterEach
    void noApplyNotifiesTwitchat() {
        // The Twitchat notification is produced once, from channel.update: never by an apply
        verify(f.dispatcher(), never()).onCategoryChangedByCatapult(any(), any(), any(), any(), any());
        verify(f.dispatcher(), never()).onCategoryChangedManually(any(), any(), any(), any());
    }

    private Write onlyWrite() {
        assertThat(f.twitch.writes()).hasSize(1);
        return f.twitch.writes().getFirst();
    }

    /**
     * {@code write} sent a binding to Twitch with this payload: its status, its CCLs and whether it sends them, ignored
     * or not, and saved ({@code persisted}: Twitch applies the CCL blocklist) or transient.
     */
    private static void assertBindingWrite(Write write, GameBinding.Status status, Set<String> ccls,
                                           boolean cclEnabled, boolean ignored, boolean persisted) {
        assertThat(write.kind()).isEqualTo(Kind.UPDATE);
        assertThat(write.status()).isEqualTo(status);
        assertThat(write.ccls()).containsExactlyInAnyOrderElementsOf(ccls);
        assertThat(write.cclEnabled()).as("cclEnabled").isEqualTo(cclEnabled);
        assertThat(write.ignored()).as("ignored").isEqualTo(ignored);
        assertThat(write.persisted()).as("persisted").isEqualTo(persisted);
    }

    /** {@code write} sent {@link #g1} as it is stored. */
    private static void assertG1Write(Write write) {
        assertBindingWrite(write, GameBinding.Status.AUTO, Set.of("ViolentGraphic"), true, false, true);
    }

    @Nested
    class Detection {

        @Test
        void live_appliesTheBinding() {
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1", "twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
        }

        @Test
        void live_sendsTheBindingsOwnCclSwitch() {
            g1.setCclEnabled(false);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertBindingWrite(onlyWrite(), GameBinding.Status.AUTO, Set.of("ViolentGraphic"), false, false, true);
        }

        @Test
        void offline_storesItPending() {
            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.pending()).containsSame(g1);
        }

        @Test
        void ignoredLive_appliesTheNoGameFallbackWithItsCcls() {
            g1.setIgnored(true);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            Write write = onlyWrite();
            assertThat(write.kind()).isEqualTo(Kind.UPDATE);
            assertThat(write.gameId()).isEqualTo("509658");
            assertThat(write.sourceName()).isEqualTo("no-game-fallback");
            assertThat(write.status()).isEqualTo(GameBinding.Status.MANUAL);
            assertBindingWrite(write, GameBinding.Status.MANUAL, Set.of("Gambling"), true, false, false);
            assertThat(f.journal())
                    .containsExactly("event:bindingUpserted g1", "twitch:update 509658 no-game-fallback");
        }

        @Test
        void ignoredOffline_storesNothingAndKeepsTheOldPending() {
            GameBinding older = f.binding("g0", GameBinding.Status.AUTO, "21779", "League of Legends");
            f.pending(older);
            g1.setIgnored(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.pending()).containsSame(older);
        }

        @Test
        void ignoredLive_noGameFallbackOff_changesNothing() {
            f.settings.setApplyDefaultOnNoGame(false);
            g1.setIgnored(true);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
        }

        @Test
        void incompleteLive_appliesTheIncompleteFallback() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            Write write = onlyWrite();
            assertThat(write.gameId()).isEqualTo("1469308723");
            assertThat(write.sourceName()).isEqualTo("incomplete-fallback");
            assertThat(write.status()).isEqualTo(GameBinding.Status.MANUAL);
            assertBindingWrite(write, GameBinding.Status.MANUAL, Set.of("ProfanityVulgarity"), true, false, false);
            assertThat(f.journal())
                    .containsExactly("event:bindingUpserted g1", "twitch:update 1469308723 incomplete-fallback");
        }

        @Test
        void incompleteOffline_storesTheFallbackPending() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.pending()).hasValueSatisfying(pending -> {
                assertThat(pending.getSourceName()).isEqualTo("incomplete-fallback");
                assertThat(pending.getTwitchGameId()).isEqualTo("1469308723");
                assertThat(pending.getCcls()).containsExactly("ProfanityVulgarity");
                assertThat(pending.getId()).isNull();
            });
        }

        @Test
        void incompleteWithoutFallback_changesNothing() {
            f.settings.setIncompleteFallbackTwitchGameId(null);
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void releasesTheRaidHold() {
            f.live(true);
            f.hold();
            f.detect(f.gameOf(g1), false);
            assertThat(f.holding()).isFalse();

            // Even when the detection itself is not applied
            GameBinding other = f.binding("g2", GameBinding.Status.AUTO, "32982", "Grand Theft Auto V");
            f.active(f.custom("Focus", "509670", "Science & Technology", true));
            f.hold();
            f.detect(f.gameOf(other), false);
            assertThat(f.holding()).isFalse();

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1", "twitch:update 27471 Minecraft",
                    "event:bindingUpserted g2");
        }

        @Test
        void interruptibleCustomBinding_isEndedFirst() {
            f.active(art);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "event:bindingUpserted g1", "twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void uninterruptibleCustomBindingBeatsAManualBinding() {
            g1.setStatus(GameBinding.Status.MANUAL);
            g1.setTwitchGameId("32982");
            CustomBinding focus = f.custom("Focus", "509670", "Science & Technology", true);
            f.active(focus);
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.activeCustom()).containsSame(focus);
        }

        @Test
        void firstDetectionSinceStart_keepsAnInterruptibleCustomBinding() {
            f.active(art);
            f.live(true);

            f.detect(f.gameOf(g1), true);

            assertThat(f.journal()).containsExactly("event:bindingUpserted g1");
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void applyAddsNoAttributionOfItsOwn() {
            f.live(true);

            f.detect(f.gameOf(g1), false);

            assertThat(onlyWrite().subject()).isEmpty();
        }

        @Test
        void applyKeepsTheCallersAttribution() {
            UUID other = UUID.randomUUID();
            f.live(true);

            DebugContext.runAs(other, () -> f.detect(f.gameOf(g1), false));

            assertThat(onlyWrite().subject()).contains(other);
        }
    }

    @Nested
    class GameClosed {

        @BeforeEach
        void playingG1() {
            f.playing(f.gameOf(g1));
            f.pending(g1);
        }

        @Test
        void live_appliesTheNoGameFallback_andDropsThePending() {
            f.live(true);

            f.closeGame();

            assertThat(f.journal()).containsExactly("twitch:update 509658 no-game-fallback");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("Gambling"), true, false, false);
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void offline_onlyDropsThePending() {
            f.closeGame();

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void duringARaidHold_changesNothing() {
            f.live(true);
            f.hold();

            f.closeGame();

            assertThat(f.journal()).isEmpty();
            assertThat(f.holding()).isTrue();
        }

        @Test
        void holdTakenWhileReadingTheSettings_changesNothing() {
            f.live(true);
            f.whenSettingsRead(f::hold);

            f.closeGame();

            assertThat(f.journal()).isEmpty();
            assertThat(f.holding()).isTrue();
        }

        @Test
        void withACustomBinding_changesNothing() {
            f.active(art);
            f.live(true);

            f.closeGame();

            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).containsSame(art);
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void withoutADefaultCategory_changesNothing() {
            f.settings.setNoGameTwitchGameId(null);
            f.live(true);

            f.closeGame();

            assertThat(f.journal()).isEmpty();
        }
    }

    @Nested
    class StreamStart {

        @BeforeEach
        void noDefaultOnStart() {
            f.settings.setApplyDefaultOnStreamStart(false);
        }

        @Test
        void customBindingIsReapplied_andThePendingKept() {
            f.active(art);
            f.pending(g1);

            f.streamOnline();

            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("SexualThemes"), true, false, false);
            assertThat(f.pending()).containsSame(g1);
        }

        @Test
        void customBindingIsReapplied_withoutABotCheckOfItsOwn() {
            f.bot(false);
            f.active(art);

            f.streamOnline();

            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art");
        }

        @Test
        void customBindingBeatsAPendingManualBinding_andTheDefault() {
            f.settings.setApplyDefaultOnStreamStart(true);
            g1.setStatus(GameBinding.Status.MANUAL);
            f.pending(g1);
            f.active(art);

            f.streamOnline();

            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art");
            assertThat(f.pending()).containsSame(g1);
        }

        @Test
        void defaultOnStart_resets_andDropsThePending() {
            f.settings.setApplyDefaultOnStreamStart(true);
            f.pending(g1);

            f.streamOnline();

            assertThat(f.journal()).containsExactly("twitch:reset");
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void pendingIsApplied_thenDropped() {
            f.pending(g1);

            f.streamOnline();

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void nothingPending_changesNothing() {
            f.streamOnline();

            assertThat(f.journal()).isEmpty();
        }

        @Test
        void twitchThrows_keepsThePending() {
            f.pending(g1);
            f.twitch.throwNext(new IllegalStateException("database down"));

            assertThrows(IllegalStateException.class, f::streamOnline);

            assertThat(f.pending()).containsSame(g1);
        }
    }

    @Nested
    class StreamEnd {

        @Test
        void endsTheCustomBinding_dropsPending_releasesHold_thenResets() {
            f.live(true);
            f.active(art);
            f.pending(g1);
            f.hold();

            f.streamOffline();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null", "twitch:reset");
            assertThat(f.activeCustom()).isEmpty();
            assertThat(f.pending()).isEmpty();
            assertThat(f.holding()).isFalse();
        }

        @Test
        void defaultOff_changesNothingOnTwitch() {
            f.settings.setApplyDefaultOnStreamEnd(false);
            f.live(true);
            f.pending(g1);

            f.streamOffline();

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).isEmpty();
        }
    }

    @Nested
    class BindingEdit {

        @Test
        void editingThePlayedBinding_live_pushesIt() {
            f.playing(f.gameOf(g1));
            f.live(true);

            f.setTwitchGame(g1, "33214", "Fortnite");

            assertThat(f.journal()).containsExactly("twitch:update 33214 Minecraft");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("ViolentGraphic"), true, false, true);
        }

        @Test
        void editingThePlayedBinding_offline_storesItPending() {
            f.playing(f.gameOf(g1));

            f.setTwitchGame(g1, "33214", "Fortnite");

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).containsSame(g1);
            assertThat(g1.getTwitchGameId()).isEqualTo("33214");
        }

        @Test
        void editingThePlayedIncompleteBinding_appliesTheIncompleteFallback() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.playing(f.gameOf(g1));
            f.live(true);

            f.toggleIgnored(g1, false);

            assertThat(f.journal()).containsExactly("twitch:update 1469308723 incomplete-fallback");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("ProfanityVulgarity"), true, false,
                    false);
        }

        @Test
        void editingThePlayedIncompleteBinding_offline_storesTheFallbackPending() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.playing(f.gameOf(g1));

            f.toggleIgnored(g1, false);

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).hasValueSatisfying(
                    pending -> assertThat(pending.getSourceName()).isEqualTo("incomplete-fallback"));
        }

        @Test
        void editingAnotherBinding_onlySavesIt() {
            GameBinding other = f.binding("g2", GameBinding.Status.AUTO, "32982", "Grand Theft Auto V");
            f.playing(f.gameOf(other));
            f.live(true);

            f.setTwitchGame(g1, "33214", "Fortnite");

            assertThat(f.journal()).isEmpty();
            assertThat(g1.getTwitchGameId()).isEqualTo("33214");
            assertThat(g1.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        }

        @Test
        void ignoringThePlayedBinding_appliesTheNoGameFallback() {
            f.playing(f.gameOf(g1));
            f.live(true);

            f.toggleIgnored(g1, true);

            Write write = onlyWrite();
            assertBindingWrite(write, GameBinding.Status.MANUAL, Set.of("Gambling"), true, false, false);
            assertThat(f.journal()).containsExactly("twitch:update 509658 no-game-fallback");
        }

        @Test
        void ignoringThePlayedBinding_offlineOrDuringARaidHold_changesNothing() {
            GameBinding older = f.binding("g0", GameBinding.Status.AUTO, "21779", "League of Legends");
            f.pending(older);
            f.playing(f.gameOf(g1));
            f.toggleIgnored(g1, true);

            f.live(true);
            f.hold();
            f.toggleIgnored(g1, true);

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).containsSame(older);
            assertThat(f.holding()).isTrue();
        }

        @Test
        void editingThePlayedManualBinding_underACustomBinding_onlySavesIt() {
            g1.setStatus(GameBinding.Status.MANUAL);
            f.playing(f.gameOf(g1));
            f.live(true);
            f.active(art);

            f.setTwitchGame(g1, "33214", "Fortnite");

            assertThat(f.journal()).isEmpty();
            assertThat(g1.getTwitchGameId()).isEqualTo("33214");
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void resettingTws_pushesThePlayedBinding() {
            g1.setStatus(GameBinding.Status.MANUAL);
            g1.setTwitchGameId("33214");
            f.mapping(g1, "27471", "Minecraft");
            f.playing(f.gameOf(g1));
            f.live(true);

            f.resetTws(g1);

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertThat(onlyWrite().status()).isEqualTo(GameBinding.Status.MAPPED);
            assertThat(onlyWrite().persisted()).isTrue();
        }

        @Test
        void resettingTws_categoryUnchanged_doesNotPush() {
            f.mapping(g1, "27471", "Minecraft");
            f.playing(f.gameOf(g1));
            f.live(true);

            f.resetTws(g1);

            assertThat(f.journal()).isEmpty();
            assertThat(g1.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        }

        @Test
        void resettingTwsOfAnotherBinding_onlySavesIt() {
            GameBinding other = f.binding("g2", GameBinding.Status.MANUAL, "33214", "Fortnite");
            f.mapping(other, "32982", "Grand Theft Auto V");
            f.playing(f.gameOf(g1));
            f.live(true);

            f.resetTws(other);

            assertThat(f.journal()).isEmpty();
            assertThat(other.getTwitchGameId()).isEqualTo("32982");
        }

        @Test
        void refreshingIncompleteBindings_pushesThePlayedOne_asItsChannel() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            g1.setTwitchGameId(null);
            f.mapping(g1, "27471", "Minecraft");
            GameBinding other = f.binding("g2", GameBinding.Status.INCOMPLETE, null, "Grand Theft Auto V");
            f.mapping(other, "32982", "Grand Theft Auto V");
            f.playing(f.gameOf(g1));
            f.live(true);

            f.refreshIncompleteBindings();

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertThat(onlyWrite().status()).isEqualTo(GameBinding.Status.MAPPED);
            assertThat(onlyWrite().subject()).contains(f.user.getId());
            assertThat(other.getStatus()).isEqualTo(GameBinding.Status.MAPPED);
        }

        @Test
        void twitchatBindGameCategory_isAnEdit_andKeepsTheCustomBinding() {
            f.playing(f.gameOf(g1));
            f.live(true);
            f.active(art);

            Result result = f.twitchat(TwitchatActionType.BIND_GAME_CATEGORY, Map.of(
                    "bindingId", g1.getId().toString(), "newGameId", "33214", "newGameName", "Fortnite"));

            assertThat(result).isEqualTo(Result.EXECUTED);
            assertThat(f.journal()).isEmpty();
            assertThat(g1.getTwitchGameId()).isEqualTo("33214");
            assertThat(g1.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
            assertThat(f.activeCustom()).containsSame(art);
        }
    }

    @Nested
    class MappingChange {

        @Test
        void live_reportsTwitchsOutcome() {
            f.live(true);
            f.twitch.answer(ChannelUpdateOutcome.FAILED);

            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.FAILED);

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
        }

        @Test
        void offline_isSkipped_andStoresNothing() {
            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void ignoredLive_appliesTheNoGameFallbackWithItsCcls() {
            g1.setIgnored(true);
            f.live(true);

            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.APPLIED);

            assertThat(f.journal()).containsExactly("twitch:update 509658 no-game-fallback");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("Gambling"), true, false, false);
        }

        @Test
        void incompleteLive_appliesTheFallback() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.live(true);

            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.APPLIED);

            assertThat(f.journal()).containsExactly("twitch:update 1469308723 incomplete-fallback");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("ProfanityVulgarity"), true, false,
                    false);
        }

        @Test
        void incompleteOffline_isSkipped_withoutPending() {
            g1.setStatus(GameBinding.Status.INCOMPLETE);

            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

            assertThat(f.journal()).isEmpty();
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void underACustomBinding_isSkipped_andKeepsIt() {
            f.active(art);
            f.live(true);

            assertThat(f.mappingChanged(g1)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void twitchThrows_propagates() {
            f.live(true);
            f.twitch.throwNext(new IllegalStateException("database down"));

            assertThrows(IllegalStateException.class, () -> f.mappingChanged(g1));
        }
    }

    @Nested
    class CustomBindings {

        @Test
        void activateOffline_appliesAndRecords() {
            assertThat(f.activate(art)).isEqualTo(ActivationResult.APPLIED);

            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art",
                    "event:customBindingChanged " + art.getId());
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("SexualThemes"), true, false, false);
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void activate_twitchFailed_recordsNothing() {
            f.twitch.answer(ChannelUpdateOutcome.FAILED);

            assertThat(f.activate(art)).isEqualTo(ActivationResult.FAILED);

            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art");
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void activate_botOff_sendsNothing() {
            f.bot(false);

            assertThat(f.activate(art)).isEqualTo(ActivationResult.BOT_DISABLED);

            assertThat(f.twitch.writes()).isEmpty();
            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void updatingTheActiveOne_reappliesIt_andKeepsItWhenTwitchFails() {
            f.active(art);
            f.twitch.answer(ChannelUpdateOutcome.FAILED);

            UpdateResult result = f.update(art);

            assertThat(result.reapplied()).isEqualTo(ActivationResult.FAILED);
            assertThat(f.journal()).containsExactly("twitch:update 509660 custom:Art");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("SexualThemes"), true, false, false);
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void deactivateLive_appliesTheOpenGame() {
            f.active(art);
            f.playing(f.gameOf(g1));
            f.live(true);

            f.deactivate();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "event:bindingUpserted g1", "twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
        }

        @Test
        void deactivateLiveWithoutGame_appliesTheNoGameFallback() {
            f.active(art);
            f.live(true);

            f.deactivate();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "twitch:update 509658 no-game-fallback");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of("Gambling"), true, false, false);
        }

        @Test
        void deactivateOffline_storesTheGamePending_thenResets() {
            f.active(art);
            f.playing(f.gameOf(g1));

            f.deactivate();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "event:bindingUpserted g1", "twitch:reset");
            assertThat(f.pending()).containsSame(g1);
        }

        @Test
        void deactivateOfflineWithoutGame_resets() {
            f.active(art);

            f.deactivate();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null", "twitch:reset");
            assertThat(f.pending()).isEmpty();
        }

        @Test
        void deactivateOffline_endOfStreamDefaultOff_onlyStoresTheGame() {
            f.settings.setApplyDefaultOnStreamEnd(false);
            f.active(art);
            f.playing(f.gameOf(g1));

            f.deactivate();

            assertThat(f.journal()).containsExactly("event:customBindingChanged null", "event:bindingUpserted g1");
            assertThat(f.pending()).containsSame(g1);
        }

        @Test
        void deactivate_keepsTheRaidHold() {
            f.active(art);
            f.playing(f.gameOf(g1));
            f.live(true);
            f.hold();

            f.deactivate();

            assertThat(f.holding()).isTrue();
            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "event:bindingUpserted g1", "twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
        }

        @Test
        void deletingTheActiveOne_deactivatesItFirst() {
            f.active(art);
            f.playing(f.gameOf(g1));
            f.live(true);

            f.delete(art);

            assertThat(f.journal()).containsExactly("event:customBindingChanged null",
                    "event:bindingUpserted g1", "twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
            assertThat(f.activeCustom()).isEmpty();
        }
    }

    @Nested
    class ManualChoices {

        private CustomBinding focus;

        @BeforeEach
        void uninterruptibleActive() {
            focus = f.custom("Focus", "509670", "Science & Technology", true);
            f.active(focus);
        }

        @Test
        void setGame_sendsAManualCategoryWithoutCcls_thenEndsTheCustomBinding() {
            f.twitch.category("Celeste", "504461");

            f.setGame("Celeste", null);

            assertThat(f.journal()).containsExactly("twitch:update 504461 Celeste",
                    "event:customBindingChanged null");
            assertBindingWrite(onlyWrite(), GameBinding.Status.MANUAL, Set.of(), true, false, false);
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void setGame_skippedByTwitch_stillEndsTheCustomBinding() {
            f.twitch.category("Celeste", "504461");
            f.twitch.answer(ChannelUpdateOutcome.SKIPPED);

            f.setGame("Celeste", null);

            assertThat(f.journal()).containsExactly("twitch:update 504461 Celeste",
                    "event:customBindingChanged null");
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void setGame_unresolved_sendsANullCategory() {
            f.setGame("Celeste", null);

            assertThat(f.journal()).containsExactly("twitch:update null Celeste",
                    "event:customBindingChanged null");
        }

        @Test
        void setGame_twitchThrows_keepsTheCustomBinding() {
            f.twitch.category("Celeste", "504461");
            f.twitch.throwNext(new IllegalStateException("database down"));

            assertThrows(IllegalStateException.class, () -> f.setGame("Celeste", null));

            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).containsSame(focus);
        }

        @ParameterizedTest
        @EnumSource(value = TwitchatActionType.class, names = {"REVERT_CATEGORY", "REVERT_TO_APP_CATEGORY"})
        void revert_setsOnlyTheCategory_thenEndsTheCustomBinding(TwitchatActionType type) {
            Result result = f.twitchat(type, Map.of("gameId", "509658", "gameName", "Just Chatting"));

            assertThat(result).isEqualTo(Result.EXECUTED);
            assertThat(f.journal()).containsExactly("twitch:set 509658", "event:customBindingChanged null");
            assertThat(onlyWrite().kind()).isEqualTo(Kind.SET_CATEGORY);
            assertThat(f.activeCustom()).isEmpty();
        }

        @Test
        void revert_twitchThrows_keepsTheCustomBinding() {
            f.twitch.throwNext(new IllegalStateException("Twitch refused"));

            assertThrows(IllegalStateException.class, () -> f.twitchat(TwitchatActionType.REVERT_CATEGORY,
                    Map.of("gameId", "509658", "gameName", "Just Chatting")));

            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).containsSame(focus);
        }
    }

    @Nested
    class Raid {

        @BeforeEach
        void recentlyPlayedG1() {
            f.playing(f.gameOf(g1));
        }

        @Test
        void putsTheRecentGameBack_andHolds() {
            // Offline, the game closed a moment ago: no live check
            f.closeGame();

            f.raid("509658");

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertG1Write(onlyWrite());
            assertThat(f.holding()).isTrue();
        }

        @Test
        void holdIsTakenBeforeTheCategoryIsSent() {
            AtomicBoolean holdingAtWrite = new AtomicBoolean();
            f.twitch.onWrite(w -> holdingAtWrite.set(f.holding()));

            f.raid("509658");

            assertThat(f.twitch.writes()).hasSize(1);
            assertThat(holdingAtWrite).isTrue();
        }

        @Test
        void alreadyOnIt_holdsWithoutWriting() {
            f.raid("27471");

            assertThat(f.journal()).isEmpty();
            assertThat(f.holding()).isTrue();
        }

        @Test
        void twitchFails_releasesTheHold() {
            f.twitch.answer(ChannelUpdateOutcome.FAILED);

            f.raid("509658");

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertThat(f.holding()).isFalse();
        }

        @Test
        void twitchSkips_releasesTheHold() {
            f.twitch.answer(ChannelUpdateOutcome.SKIPPED);

            f.raid("509658");

            assertThat(f.journal()).containsExactly("twitch:update 27471 Minecraft");
            assertThat(f.holding()).isFalse();
        }

        @Test
        void twitchThrows_releasesTheHold() {
            AtomicBoolean holdingAtWrite = new AtomicBoolean();
            f.twitch.onWrite(w -> holdingAtWrite.set(f.holding()));
            f.twitch.throwNext(new IllegalStateException("database down"));

            f.raid("509658");

            assertThat(f.twitch.thrown()).as("the write was attempted and threw").isEqualTo(1);
            assertThat(holdingAtWrite).as("held while Twitch was called").isTrue();
            assertThat(f.holding()).isFalse();
            assertThat(f.journal()).isEmpty();
        }

        @Test
        void restoreOffOrGateClosed_changesNothing() {
            f.settings.setRaidRestoreGameMinutes(null);
            f.raid("509658");
            f.settings.setRaidRestoreGameMinutes(0);
            f.raid("509658");
            f.settings.setRaidRestoreGameMinutes(10);
            f.raidGate(false);
            f.raid("509658");

            assertThat(f.journal()).isEmpty();
            assertThat(f.holding()).isFalse();
        }

        @Test
        void underACustomBinding_changesNothing() {
            f.active(art);

            f.raid("509660");

            assertThat(f.journal()).isEmpty();
            assertThat(f.holding()).isFalse();
            assertThat(f.activeCustom()).containsSame(art);
        }

        @Test
        void ignoredIncompleteOrUncategorised_changesNothing() {
            g1.setIgnored(true);
            f.raid("509658");
            assertThat(f.holding()).isFalse();

            g1.setIgnored(false);
            g1.setStatus(GameBinding.Status.INCOMPLETE);
            f.raid("509658");
            assertThat(f.holding()).isFalse();

            g1.setStatus(GameBinding.Status.AUTO);
            g1.setTwitchGameId(null);
            f.raid("509658");
            assertThat(f.holding()).isFalse();

            assertThat(f.journal()).isEmpty();
        }

        @Test
        void isAttributedToTheRaidingMember() {
            f.raid("509658");

            assertThat(onlyWrite().subject()).contains(f.user.getId());
        }
    }

    @Nested
    class NoGameSettingsSaved {

        @Test
        void liveDuringARaidHold_resets() {
            f.live(true);
            f.hold();

            f.noGameSettingsSaved();

            assertThat(f.journal()).containsExactly("twitch:reset");
            assertThat(f.holding()).isTrue();
        }

        @Test
        void offline_resets() {
            f.noGameSettingsSaved();

            assertThat(f.journal()).containsExactly("twitch:reset");
        }

        @Test
        void noGameFallbackOff_stillResets() {
            f.settings.setApplyDefaultOnNoGame(false);
            f.live(true);

            f.noGameSettingsSaved();

            assertThat(f.journal()).containsExactly("twitch:reset");
        }

        @Test
        void gameDetected_changesNothing_withoutReadingTheCustomBinding() {
            f.playing(f.gameOf(g1));
            f.active(art);
            f.live(true);
            int before = f.activationReads();

            f.noGameSettingsSaved();

            assertThat(f.journal()).isEmpty();
            assertThat(f.activationReads() - before).isZero();
        }

        @Test
        void customBindingActive_changesNothing() {
            f.active(art);
            f.live(true);

            f.noGameSettingsSaved();

            assertThat(f.journal()).isEmpty();
            assertThat(f.activeCustom()).containsSame(art);
        }
    }

    @Test
    void eachTriggerReadsTheCustomBindingAtMostOnce() {
        f.settings.setApplyDefaultOnStreamStart(false);
        GameBinding other = f.binding("g2", GameBinding.Status.AUTO, "32982", "Grand Theft Auto V");
        f.live(true);

        assertThat(readsOf(() -> f.detect(f.gameOf(g1), false))).as("detection").isEqualTo(1);
        assertThat(readsOf(f::closeGame)).as("game closed").isEqualTo(1);
        assertThat(readsOf(f::streamOnline)).as("stream start").isEqualTo(1);
        assertThat(readsOf(f::streamOffline)).as("stream end").isZero();
        assertThat(readsOf(f::noGameSettingsSaved)).as("no-game settings saved, nothing detected").isEqualTo(1);
        f.playing(f.gameOf(other));
        assertThat(readsOf(() -> f.setTwitchGame(other, "33214", "Fortnite"))).as("edit of the played binding")
                .isEqualTo(1);
        f.live(true);
        assertThat(readsOf(() -> f.mappingChanged(g1))).as("mapping").isEqualTo(1);
        assertThat(readsOf(() -> f.raid("509658"))).as("raid").isEqualTo(1);
        assertThat(readsOf(() -> f.setGame("Celeste", null))).as("!setgame").isZero();
        assertThat(readsOf(() -> f.twitchat(TwitchatActionType.REVERT_CATEGORY,
                Map.of("gameId", "509658", "gameName", "Just Chatting")))).as("revert").isZero();
        assertThat(gateChecks).as("custom bindings gate, per trigger in the same order")
                .containsExactly(1, 1, 1, 0, 1, 1, 1, 1, 0, 0);
    }

    /** The custom bindings gate evaluations of each {@link #readsOf} trigger, in order. */
    private final List<Integer> gateChecks = new java.util.ArrayList<>();

    /** The activation row's reads during {@code trigger}, with {@code art} active when it starts. */
    private int readsOf(Runnable trigger) {
        f.active(art);
        int before = f.activationReads();
        int gateBefore = f.gateChecks();
        trigger.run();
        gateChecks.add(f.gateChecks() - gateBefore);
        return f.activationReads() - before;
    }
}
