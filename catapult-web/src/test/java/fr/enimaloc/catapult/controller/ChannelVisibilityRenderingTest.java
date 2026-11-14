package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.InviteRedemptionDto;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.BattleNetData;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the per-row correctness of .binding-tw-reset-btn's spa:if: each binding
 * row must carry its own hasOverride state independently of the others, since
 * Visibility.apply on the client is scoped per-row precisely to avoid one binding's
 * update leaking into another's reset button.
 */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class ChannelVisibilityRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    /** The channel template dereferences channelSettings and the DTDD mapping, for its owner and moderators alike. */
    @BeforeEach
    void stubSettingsAndDtddMapping() {
        lenient().when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false));
        lenient().when(apiService.dtddMappingStatus(any())).thenReturn(new DtddMappingStatusDto(null, null, false, null));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void bindingTwResetButton_carriesIndependentVisibilityPerRow() throws Exception {
        BindingDto overridden = new BindingDto(
                "binding-1", "AUTO", "STEAM", "Game A", null, "Game A",
                false, false, Set.of(), true, true, Set.of("spoiler"));
        BindingDto notOverridden = new BindingDto(
                "binding-2", "AUTO", "STEAM", "Game B", null, "Game B",
                false, false, Set.of(), true, false, Set.of());

        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 2, List.of(overridden, notOverridden)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Both rows must carry the data-if breadcrumb, but only the
        // non-overridden row's button should also have picked up the 'hidden' class —
        // a shared/collapsed state between rows would make these counts diverge.
        assertThat(countOccurrences(html, "data-if=\"hasOverride\"")).isEqualTo(2);
        assertThat(countOccurrences(html, "binding-tw-reset-btn hidden")).isEqualTo(1);
        assertThat(countOccurrences(html, "binding-tw-reset-btn")).isEqualTo(2);
        // Each row also needs its own data-on breadcrumb for the generic SSE dispatcher
        // (Visibility.dispatch) to re-evaluate hasOverride per row without any JS change.
        assertThat(countOccurrences(html, "data-on=\"hasOverride:TwUpdatedEvent.tws,hasOverride:!TwResetEvent\"")).isEqualTo(2);
    }

    /** A moderator changes the channel's settings and bindings, but not its TWs, bot, game recheck or integrations. */
    @Test
    void aModerator_editsSettingsAndBindings_notWhatIsTheOwnersAlone() throws Exception {
        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", null, "Game A",
                false, true, Set.of(), true, false, Set.of());
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<mdui-tab value=\"settings\"")
                .contains("id=\"channel-ccl-settings-form\"", "id=\"channel-tw-settings-form\"",
                        "id=\"channel-no-game-settings-form\"", "id=\"channel-incomplete-fallback-settings-form\"")
                .contains("binding-ignored-toggle", "binding-delete-btn", "binding-edit-game-input", "binding-ccl-enabled")
                .doesNotContain("binding-tw-panel")
                .doesNotContain("<mdui-tab value=\"integrations\"")
                .doesNotContain("bot-toggle-row")
                .doesNotContain("disabled=\"disabled\"");
    }

    /** The owner's account preference shows in their Settings tab too; a moderator doesn't get it. */
    @Test
    void notificationChannels_inTheOwnersSettingsTab_only() throws Exception {
        when(apiService.notificationChannels()).thenReturn(new fr.enimaloc.catapult.common.dto.notification.NotificationChannelsDto(
                Set.of(fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT)));
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));

        String owner = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(owner).contains("id=\"notification-channels\"", "data-channel=\"TWITCHAT\"", "data-channel=\"STREAM_START_CHAT\"");
        assertThat(openingTagForSwitch(owner, "TWITCHAT")).contains("checked");
        assertThat(openingTagForSwitch(owner, "STREAM_START_CHAT")).doesNotContain("checked");

        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));

        String moderator = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(moderator).doesNotContain("notification-channels");
    }

    private static String openingTagForSwitch(String html, String channel) {
        int at = html.indexOf("data-channel=\"" + channel + "\"");
        return html.substring(html.lastIndexOf("<mdui-switch", at), html.indexOf(">", at) + 1);
    }

    @Test
    void dangerZone_ownerOnly_andPendingShowsTheDate() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        when(apiService.accountInfo()).thenReturn(new fr.enimaloc.catapult.common.dto.UserInfoResponse(
                null, "1", "enimaloc", null, "ACTIVE", List.of(), null));
        when(apiService.accountDeletionDelayDays()).thenReturn(7);

        String active = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Both states rendered, for channel-danger-zone.js to swap in place: the pending one hidden
        assertThat(active).contains("id=\"channel-danger-zone\"", "id=\"danger-zone-delete-dialog\"",
                        "data-delay-days=\"7\"", "id=\"danger-zone-request\">",
                        "id=\"danger-zone-pending\" class=\"hidden\"", "danger-zone-cancel-btn",
                        "data-label=\"");

        when(apiService.accountInfo()).thenReturn(new fr.enimaloc.catapult.common.dto.UserInfoResponse(
                null, "1", "enimaloc", null, "PENDING_DELETION", List.of(), "2026-10-01T10:00:00Z"));

        String pending = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(pending).contains("id=\"channel-danger-zone\"", "danger-zone-cancel-btn")
                .containsAnyOf("08/10/2026", "10/08/2026")
                .contains("data-live-time=\"countdown\" datetime=\"2026-10-08T10:00:00Z\"")
                .contains("id=\"danger-zone-pending\">", "id=\"danger-zone-request\" class=\"hidden\"",
                        "danger-zone-delete-btn");

        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", null, "Game A",
                false, true, Set.of(), true, false, Set.of());
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));

        String moderator = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(moderator).doesNotContain("channel-danger-zone");
    }

    @Test
    void invitationsTab_ownerOnly_withoutRight_showsTheNoPermissionCard() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        when(apiService.invitePage()).thenReturn(new InvitePageData(false, null, null, null, null, List.of()));

        String denied = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(denied).contains("value=\"invitations\"", "id=\"channel-invite-denied\"")
                .doesNotContain("id=\"invite-regenerate-btn\"");

        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", null, "Game A",
                false, true, Set.of(), true, false, Set.of());
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));

        String moderator = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(moderator).doesNotContain("value=\"invitations\"", "channel-invite");
    }

    @Test
    void invitationsTab_showsCodeLinkAndRedemptions_orTheEmptyState() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        when(apiService.invitePage()).thenReturn(new InvitePageData(true, java.util.UUID.randomUUID(), "ABC234",
                "https://catapult.test/join?invite=ABC234", java.time.Instant.parse("2026-10-01T10:00:00Z"),
                List.of(new InviteRedemptionDto("4242", java.time.Instant.parse("2026-10-02T10:00:00Z")))));

        String used = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(used).contains("id=\"channel-invite\"", ">ABC234<", "https://catapult.test/join?invite=ABC234",
                "id=\"invite-regenerate-dialog\"", "4242")
                .doesNotContain("channel-invite-denied", "id=\"invite-redemptions-empty\"");

        when(apiService.invitePage()).thenReturn(new InvitePageData(true, java.util.UUID.randomUUID(), "ABC234",
                "https://catapult.test/join?invite=ABC234", null, List.of()));

        String unused = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(unused).contains("id=\"invite-redemptions-empty\"").doesNotContain("4242");
    }

    @Test
    void obsCard_showsTheWidgetLink_ownerOnly() throws Exception {
        when(apiService.twitchatSettings("enimaloc")).thenReturn(new fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse(
                true, "localhost", 4455, true, "11111111-2222-3333-4444-555555555555"));
        ChannelPageData withObs = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null, new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(), null, null, null, null, null,
                new ObsData(true, "localhost", 4455, true), null, null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(withObs);

        String owner = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(owner).contains("id=\"obs-widget\"", "id=\"obs-widget-regenerate-dialog\"")
                .contains("http://localhost/widget/obs/11111111-2222-3333-4444-555555555555");

        // catapult-api unreachable: a short line instead of the link, never silence.
        when(apiService.twitchatSettings("enimaloc")).thenReturn(null);
        String down = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(down).contains("id=\"obs-widget-unavailable\"").doesNotContain("id=\"obs-widget\"");

        // Without OBS (or as a moderator) the link isn't rendered at all.
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        String noObs = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(noObs).doesNotContain("obs-widget", "11111111-2222-3333-4444-555555555555");
    }

    @Test
    void commandsCard_carriesTheSummaryLinkItsFieldsAreBuiltOn() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        when(apiService.enabledChatCommands()).thenReturn(List.of(
                new fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto("!game", "VIEWERS", "game")));
        when(apiService.twitchatSettings("enimaloc")).thenReturn(new fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse(
                true, "localhost", 4455, true, "11111111-2222-3333-4444-555555555555"));

        String owner = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The read-only token's placeholder: channel-command-migration.js swaps it for a token generated on the page
        assertThat(owner).contains("id=\"command-migration\"",
                "data-summary-url=\"http://localhost/api/me/game/summary?token=&lt;READ_TOKEN&gt;\"",
                "data-summary-placeholder-url=\"http://localhost/api/me/game/summary?token=&lt;READ_TOKEN&gt;\"",
                "value=\"http://localhost/api/me/game/summary?token=&lt;READ_TOKEN&gt;")
                // The widget's token is in no bot link any more
                .doesNotContain("11111111-2222-3333-4444-555555555555");

        String french = mvc.perform(get("/spa/channel/enimaloc").locale(java.util.Locale.FRENCH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(french).contains("value=\"http://localhost/api/me/game/summary?token=&lt;JETON_LECTURE&gt;");
    }

    @Test
    void channel_cardsCarryTheirHelpButtons() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));
        when(apiService.accountInfo()).thenReturn(new fr.enimaloc.catapult.common.dto.UserInfoResponse(
                null, "1", "enimaloc", null, "ACTIVE", List.of(), null));
        when(apiService.accountDeletionDelayDays()).thenReturn(7);

        String html = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String card : List.of("status", "bindings", "ccl-settings", "no-game", "tw-settings", "connections", "danger-zone")) {
            assertThat(countOccurrences(html, "data-help=\"" + card + "\"")).as(card).isEqualTo(1);
        }
        assertThat(html).contains("icon=\"help_outline\"");
    }

    @Test
    void raidCard_onlyWhenTheExperimentOffersIt() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(false));
        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), 20, true));

        String offered = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(offered).contains("id=\"channel-raid-settings-form\"", "id=\"raid-settings-save-btn\"");
        assertThat(openingTagForId(offered, "raid-restore-enabled")).contains("checked");
        assertThat(openingTagForId(offered, "raid-restore-minutes")).contains("value=\"20\"", "min=\"1\"", "max=\"120\"");

        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), 20, false));
        String hidden = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(hidden).contains("id=\"channel-ccl-settings-form\"").doesNotContain("channel-raid-settings-form");
    }

    @Test
    void streamTitle_cardAndGameFields_onlyWhenTheExperimentOffersThem() throws Exception {
        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", "123", "Game A",
                false, true, Set.of(), true, false, Set.of(), null, List.of(), "{{GAME}} blind run");
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));
        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false, List.of(), List.of(),
                true, "Playing {{GAME}}", true, null, null, true));

        String offered = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(offered).contains("id=\"channel-title-settings-form\"", "id=\"title-settings-save-btn\"",
                "data-help=\"title-settings\"", "class=\"binding-title-panel\"");
        assertThat(openingTagForId(offered, "title-enabled-checkbox")).contains("checked");
        assertThat(openingTagForId(offered, "title-template-input")).contains("value=\"Playing {{GAME}}\"", "maxlength=\"140\"")
                .doesNotContain("disabled");
        assertThat(offered).contains("data-title-preview", "data-preview-kind=\"TEMPLATE\"",
                "data-preview-kind=\"BINDING\"", "data-preview-kind=\"NO_GAME\"", "data-preview-kind=\"INCOMPLETE\"");
        assertThat(offered).contains("value=\"{{GAME}} blind run\"", "data-value=\"value:BindingTitleEvent.title\"",
                "id=\"no-game-title-input\"", "id=\"incomplete-fallback-title-input\"");

        // A catapult-api that predates the title: nothing of it shown
        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false, List.of(), List.of(),
                null, null, null));
        String hidden = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(hidden).contains("id=\"channel-ccl-settings-form\"")
                .doesNotContain("data-title-preview", "channel-title-settings-form", "binding-title-panel", "no-game-title-input",
                        "incomplete-fallback-title-input");
    }

    /** The title fields' helper names the conditions and the drops only while their experiments offer them. */
    @Test
    void streamTitle_helperNamesConditionsAndDrops_onlyWhenOffered() throws Exception {
        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", "123", "Game A",
                false, true, Set.of(), true, false, Set.of(), null, List.of(), null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));

        when(apiService.channelSettings(any())).thenReturn(titleSettings(true, true));
        String both = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        when(apiService.channelSettings(any())).thenReturn(titleSettings(true, false));
        String conditions = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        when(apiService.channelSettings(any())).thenReturn(titleSettings(null, null));
        String neither = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String id : List.of("title-template-input", "no-game-title-input", "incomplete-fallback-title-input")) {
            assertThat(openingTagForId(both, id)).as(id).contains("{{ORIGINAL_TITLE}}", "IS_NO_GAME", "{{DROP_NAME}}");
            assertThat(openingTagForId(conditions, id)).as(id).contains("IS_NO_GAME").doesNotContain("DROP_NAME");
            assertThat(openingTagForId(neither, id)).as(id).contains("{{ORIGINAL_TITLE}}")
                    .doesNotContain("IS_NO_GAME", "DROP_NAME", "null");
        }
        assertThat(both).contains("IS_INCOMPLETE, HAS_TW");
        assertThat(neither).doesNotContain("IS_INCOMPLETE, HAS_TW", "{{DROP_END}}");
    }

    private static UserSettingsDto titleSettings(Boolean conditions, Boolean drops) {
        return new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false, List.of(), List.of(),
                true, "Playing {{GAME}}", true, null, null, false, conditions, drops);
    }

    private ChannelPageData pageWithDrops(java.util.Map<String, fr.enimaloc.catapult.common.dto.channel.DropDto> drops) {
        BindingDto delta = new BindingDto("binding-1", "AUTO", "STEAM", "Delta Force", "1282866981", "Delta Force",
                false, true, Set.of(), true, false, Set.of());
        BindingDto other = new BindingDto("binding-2", "AUTO", "STEAM", "Other", "7", "Other",
                false, true, Set.of(), true, false, Set.of());
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 2, List.of(delta, other)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null, drops);
    }

    @Test
    void dropsChipAndFilter_onlyWhenTheExperimentOffersThem() throws Exception {
        when(apiService.channelSettings(any())).thenReturn(null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(pageWithDrops(java.util.Map.of(
                "1282866981", new fr.enimaloc.catapult.common.dto.channel.DropDto("SEASON AURORA",
                        java.time.Instant.parse("2026-11-25T12:00:00Z"), "https://example.test/aurora"))));

        String offered = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(offered, "class=\"binding-drops-chip\"")).isEqualTo(1);
        assertThat(offered).contains("href=\"https://example.test/aurora\"", "target=\"_blank\"",
                "rel=\"noopener noreferrer\"", "SEASON AURORA", "25/11", "source=DROPS");

        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(pageWithDrops(null));
        String hidden = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(hidden).doesNotContain("binding-drops-chip", "source=DROPS");
    }

    @Test
    void raidCard_offSuggestsFifteenMinutes() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(false));
        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, true));

        String html = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(openingTagForId(html, "raid-restore-enabled")).doesNotContain("checked");
        assertThat(openingTagForId(html, "raid-restore-minutes")).contains("value=\"15\"", "disabled");
    }

    /**
     * A binding row and both fallback cards carry the channel diagram of their extension layout, saved to their own
     * route: six slots each, a slot the layout sets showing its extension by name.
     */
    @Test
    void bindingRowAndFallbacks_carryTheirExtensionLayout() throws Exception {
        BindingDto binding = new BindingDto("binding-1", "AUTO", "STEAM", "Game A", "123", "Game A",
                false, true, Set.of(), true, false, Set.of(), null,
                List.of(new ExtensionSlotDto(TwitchExtensionsDto.Type.OVERLAY, 1, "mock-overlay", "Live Map")));
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", false, true, true, null,
                new PagedBindings(0, 1, 1, List.of(binding)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null));
        when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false,
                List.of(new ExtensionSlotDto(TwitchExtensionsDto.Type.PANEL, 1, "mock-panel", "Build Guide")),
                List.of(new ExtensionSlotDto(TwitchExtensionsDto.Type.COMPONENT, 2, null, null))));

        String html = mvc.perform(get("/spa/channel/enimaloc")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(html, "data-slot=\"")).isEqualTo(18);
        assertThat(html).contains("data-save-url=\"/channel/enimaloc/bindings/binding-1/extensions\"",
                "data-save-url=\"/channel/enimaloc/settings/no-game/extensions\"",
                "data-save-url=\"/channel/enimaloc/settings/incomplete-fallback/extensions\"",
                ">Live Map<", ">Build Guide<");
        // The row's layout comes after its CCLs, each card's inside it.
        assertThat(html.indexOf("data-extensions=\"binding\"")).isGreaterThan(html.indexOf("binding-ccl-panel"));
        int noGame = html.indexOf("id=\"channel-no-game-settings-form\"");
        int fallback = html.indexOf("id=\"channel-incomplete-fallback-settings-form\"");
        assertThat(html.indexOf("data-extensions=\"no-game\"")).isBetween(noGame, fallback);
        assertThat(html.indexOf("data-extensions=\"incomplete-fallback\"")).isGreaterThan(fallback);
    }

    /** A MAPPED binding reads as automatic, with the mention that an admin mapping set its category. */
    @Test
    void mappedBinding_showsAsAutomatic_withTheAdminMention() throws Exception {
        BindingDto mapped = new BindingDto("binding-mapped", "MAPPED", "STEAM", "Game A", "123", "Game A",
                false, true, Set.of(), true, false, Set.of());
        BindingDto auto = new BindingDto("binding-auto", "AUTO", "STEAM", "Game B", "456", "Game B",
                false, true, Set.of(), true, false, Set.of());
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 2, List.of(mapped, auto)),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .doesNotContain(">MAPPED<")
                .contains("Category set by the admin");
        assertThat(countOccurrences(html, "Category set by the admin")).isEqualTo(1);
        // No dedicated filter: MAPPED bindings come back under the automatic one.
        assertThat(html).doesNotContain("status=MAPPED");
    }

    private ChannelPageData channelPageData(boolean isLive) {
        return channelPageData(isLive, null);
    }

    private ChannelPageData channelPageData(boolean isLive, MinecraftData minecraft) {
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, isLive, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, minecraft, null, null, null);
    }

    private ChannelPageData channelPageData(BattleNetData battleNet) {
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, battleNet, null, null, null, null);
    }

    private String battleNetPage(BattleNetData battleNet) throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(battleNet));
        return mvc.perform(get("/spa/channel/enimaloc").locale(java.util.Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Extracts the opening tag text for the first element with the given id. */
    private static String openingTagForId(String html, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[a-zA-Z0-9-]+[^>]*\\bid=\"" + id + "\"[^>]*>")
                .matcher(html);
        return m.find() ? m.group() : null;
    }

    @Test
    void streamStateChips_live_showsLiveHidesOffline() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String liveTag = openingTagForId(html, "channel-state-live");
        String offlineTag = openingTagForId(html, "channel-state-offline");
        assertThat(liveTag).contains("data-if=\"live\"").contains("data-on=\"live:ChannelLiveStateEvent.state\"").doesNotContain("hidden");
        assertThat(offlineTag).contains("data-if=\"offline\"").contains("data-on=\"offline:ChannelLiveStateEvent.!state\"").contains("hidden");
    }

    @Test
    void streamStateChips_offline_showsOfflineHidesLive() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(false));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String liveTag = openingTagForId(html, "channel-state-live");
        String offlineTag = openingTagForId(html, "channel-state-offline");
        assertThat(liveTag).contains("data-if=\"live\"").contains("hidden");
        assertThat(offlineTag).contains("data-if=\"offline\"").doesNotContain("hidden");
    }

    private ChannelPageData channelPageDataWithGame(GameDto currentGame) {
        return new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, currentGame,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, null);
    }

    @Test
    void currentGame_gameDetected_showsGameHidesNoGameMessage() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageDataWithGame(new GameDto("binding-1", "Jusant", "STEAM")));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String gameTag = openingTagForId(html, "channel-current-game");
        String noGameTag = openingTagForId(html, "channel-no-game");
        assertThat(gameTag).contains("data-if=\"hasGame\"").doesNotContain("hidden");
        assertThat(noGameTag).contains("data-if=\"noGame\"").contains("hidden");
        assertThat(html).contains(">Jusant<");
    }

    @Test
    void currentGame_noGameDetected_showsNoGameMessageHidesGame() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageDataWithGame(null));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String gameTag = openingTagForId(html, "channel-current-game");
        String noGameTag = openingTagForId(html, "channel-no-game");
        assertThat(gameTag).contains("data-if=\"hasGame\"").contains("hidden");
        assertThat(noGameTag).contains("data-if=\"noGame\"").doesNotContain("hidden")
                .contains("data-on=\"noGame:GameChangedEvent.!sourceName\"");
        assertThat(gameTag).contains("data-on=\"hasGame:GameChangedEvent.sourceName\"");
    }

    @Test
    void spaValue_rendersDataValueForPropertySyncs() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Pins the two spa:value cases that have no spa:if of their own to anchor on —
        // a page-level checkbox sync (#channel-bot-toggle) and a text sync
        // (#channel-current-game), both resolved purely from data-value.
        String botToggleTag = openingTagForId(html, "channel-bot-toggle");
        assertThat(botToggleTag).contains("data-value=\"checked:BotStateChangedEvent.state\"");

        String gameTag = openingTagForId(html, "channel-current-game");
        assertThat(gameTag).contains("data-value=\"textContent:GameChangedEvent.sourceName\"");
    }

    @Test
    void minecraftStatusPending_showsPendingCaseAndCheckButtonOnly() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageData(true, new MinecraftData("PENDING", "Steve123", null)));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Only the PENDING case div and the check button should render visible; every
        // other status div/button carries its own unique flag name (not a shared one —
        // see the ledger/commit for the bug that caused when they weren't) and ends up
        // hidden.
        assertThat(countOccurrences(html, "data-if=\"casePending\"")).isEqualTo(1);
        assertThat(openingTagForId(html, "minecraft-enroll-btn")).contains("hidden");
        assertThat(openingTagForId(html, "minecraft-check-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "minecraft-disconnect-btn")).contains("hidden");
    }

    /** Every service account full: the card says so, and offers nothing to do. */
    @Test
    void minecraftStatusFull_saysEveryAccountIsFull_withoutAnyButton() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any()))
                .thenReturn(channelPageData(true, new MinecraftData("FULL", null, null)));

        String html = mvc.perform(get("/spa/channel/enimaloc").locale(java.util.Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fullCase = html.substring(html.lastIndexOf('<', html.indexOf("data-if=\"caseFull\"")));
        assertThat(fullCase.substring(0, fullCase.indexOf('>'))).doesNotContain("hidden");
        assertThat(html).contains("Every Catapult Minecraft account is full for now");
        assertThat(openingTagForId(html, "minecraft-enroll-btn")).contains("hidden");
        assertThat(openingTagForId(html, "minecraft-check-btn")).contains("hidden");
        assertThat(openingTagForId(html, "minecraft-disconnect-btn")).contains("hidden");
    }

    @Test
    void battleNetCardRendersOneCasePerStatus() throws Exception {
        String html = battleNetPage(new BattleNetData("NONE", null, null));

        for (String flag : List.of("caseNone", "casePending", "caseRejected", "caseAccepted", "caseRemoved", "caseFull")) {
            assertThat(countOccurrences(html, "data-if=\"" + flag + "\"")).as(flag).isEqualTo(1);
        }
        assertThat(html).contains("data-conn=\"battlenet\"");
        assertThat(html).contains("data-on=\"caseNone:BattleNetLinkEvent.status=NONE\"");
        assertThat(html).contains("data-on=\"caseAccepted:BattleNetLinkEvent.status=ACCEPTED\"");
        String noneCase = html.substring(html.lastIndexOf('<', html.indexOf("data-if=\"caseNone\"")));
        assertThat(noneCase.substring(0, noneCase.indexOf('>'))).doesNotContain("hidden");
        String acceptedCase = html.substring(html.lastIndexOf('<', html.indexOf("data-if=\"caseAccepted\"")));
        assertThat(acceptedCase.substring(0, acceptedCase.indexOf('>'))).contains("hidden");
        assertThat(openingTagForId(html, "battlenet-connect-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "battlenet-disconnect-btn")).contains("hidden");
        // Same look as the Xbox account link: a plain button, not a segmented one.
        assertThat(openingTagForId(html, "battlenet-connect-btn")).startsWith("<mdui-button ")
                .doesNotContain("variant=");
        assertThat(openingTagForId(html, "battlenet-disconnect-btn")).startsWith("<mdui-button ")
                .contains("variant=\"text\"");
    }

    @Test
    void pendingCaseShowsTheBotTag() throws Exception {
        String html = battleNetPage(new BattleNetData("PENDING", "Me#1", "CatapultBot#4242"));

        String pendingCase = html.substring(html.lastIndexOf('<', html.indexOf("data-if=\"casePending\"")));
        assertThat(pendingCase.substring(0, pendingCase.indexOf('>'))).doesNotContain("hidden");
        assertThat(html).contains("CatapultBot#4242")
                .contains("data-value=\"textContent:BattleNetLinkEvent.serviceAccountBattleTag\"");
        assertThat(openingTagForId(html, "battlenet-connect-btn")).contains("hidden");
        assertThat(openingTagForId(html, "battlenet-disconnect-btn")).contains("hidden");
    }

    @Test
    void acceptedCaseShowsTheStreamerTagAndTheDisconnectButton() throws Exception {
        String html = battleNetPage(new BattleNetData("ACCEPTED", "Me#1", "CatapultBot#4242"));

        assertThat(html).contains("Connected as").contains("Me#1");
        assertThat(openingTagForId(html, "battlenet-disconnect-btn")).doesNotContain("hidden")
                .contains("data-on=\"showDisconnect:BattleNetLinkEvent.status=ACCEPTED\"");
        assertThat(openingTagForId(html, "battlenet-connect-btn")).contains("hidden");
    }

    @Test
    void rejectedAndRemovedOfferTheConnectButtonAgain() throws Exception {
        for (String status : List.of("INVITE_REJECTED", "REMOVED")) {
            String html = battleNetPage(new BattleNetData(status, "Me#1", "CatapultBot#4242"));
            assertThat(openingTagForId(html, "battlenet-connect-btn")).as(status).doesNotContain("hidden");
            assertThat(openingTagForId(html, "battlenet-disconnect-btn")).as(status).contains("hidden");
        }
    }

    @Test
    void aHostileBattleTag_isRenderedEscaped() throws Exception {
        String html = battleNetPage(new BattleNetData("ACCEPTED", "<b>x</b>", "<i>y</i>"));

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>").doesNotContain("<i>y</i>");
    }

    @Test
    void noVerifyButton() throws Exception {
        String html = battleNetPage(new BattleNetData("PENDING", "Me#1", "CatapultBot#4242"));

        assertThat(html).doesNotContain("battlenet-check-btn").doesNotContain("battlenet-sync-btn");
    }

    private String ankamaPage(fr.enimaloc.catapult.common.dto.channel.AnkamaData ankama) throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null, new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(), null, null, null, null, null, null, null, null, null, null,
                ankama));
        return mvc.perform(get("/spa/channel/enimaloc").locale(java.util.Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static fr.enimaloc.catapult.common.dto.channel.AnkamaData ankama(String status) {
        return new fr.enimaloc.catapult.common.dto.channel.AnkamaData(status, "Me#1", "CatapultBot#4242");
    }

    @Test
    void ankamaCardRendersOneCasePerStatus() throws Exception {
        String html = ankamaPage(new fr.enimaloc.catapult.common.dto.channel.AnkamaData("NONE", null, null));

        for (String flag : List.of("caseNone", "casePending", "caseRejected", "caseAccepted", "caseRemoved", "caseFull")) {
            assertThat(countOccurrences(html, "data-if=\"" + flag + "\"")).as(flag).isEqualTo(1);
        }
        assertThat(html).contains("data-conn=\"ankama\"");
        assertThat(html).contains("data-on=\"caseNone:AnkamaLinkEvent.status=NONE\"");
        assertThat(html).contains("data-on=\"caseAccepted:AnkamaLinkEvent.status=ACCEPTED\"");
        assertThat(html).contains("placeholder=\"Pseudo#1234\"");
        assertThat(openingTagForId(html, "ankama-name-input")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "ankama-connect-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "ankama-resend-btn")).contains("hidden");
        assertThat(openingTagForId(html, "ankama-disconnect-btn")).contains("hidden");
    }

    @Test
    void ankamaPendingCase_showsTheBotAndTheResendButton() throws Exception {
        String html = ankamaPage(ankama("PENDING"));

        String pendingCase = html.substring(html.lastIndexOf('<', html.indexOf("data-if=\"casePending\"")));
        assertThat(pendingCase.substring(0, pendingCase.indexOf('>'))).doesNotContain("hidden");
        assertThat(html).contains("Accept the invitation from").contains("CatapultBot#4242")
                .contains("data-value=\"textContent:AnkamaLinkEvent.serviceAccountTag\"");
        assertThat(openingTagForId(html, "ankama-resend-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "ankama-connect-btn")).contains("hidden");
        assertThat(openingTagForId(html, "ankama-name-input")).contains("hidden");
        // A mistyped Pseudo#tag can be cancelled while pending.
        assertThat(openingTagForId(html, "ankama-disconnect-btn")).doesNotContain("hidden")
                .contains("data-on=\"showDisconnect:AnkamaLinkEvent.status=PENDING/ACCEPTED\"");
    }

    @Test
    void ankamaAcceptedCase_showsTheNameTagAndTheDisconnectButton() throws Exception {
        String html = ankamaPage(ankama("ACCEPTED"));

        assertThat(html).contains("Connected as").contains("Me#1");
        assertThat(openingTagForId(html, "ankama-disconnect-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "ankama-connect-btn")).contains("hidden");
        assertThat(openingTagForId(html, "ankama-resend-btn")).contains("hidden");
    }

    @Test
    void ankamaRejectedAndRemoved_offerTheFormAgain() throws Exception {
        for (String status : List.of("INVITE_REJECTED", "REMOVED")) {
            String html = ankamaPage(ankama(status));
            assertThat(openingTagForId(html, "ankama-connect-btn")).as(status).doesNotContain("hidden");
            assertThat(openingTagForId(html, "ankama-name-input")).as(status).doesNotContain("hidden");
            assertThat(openingTagForId(html, "ankama-disconnect-btn")).as(status).contains("hidden");
        }
    }

    @Test
    void ankamaFull_hidesTheForm() throws Exception {
        String html = ankamaPage(ankama("FULL"));

        assertThat(html).contains("Every Catapult Ankama account is full for now");
        assertThat(openingTagForId(html, "ankama-connect-btn")).contains("hidden");
        assertThat(openingTagForId(html, "ankama-name-input")).contains("hidden");
    }

    @Test
    void aHostileAnkamaTag_isRenderedEscaped() throws Exception {
        String html = ankamaPage(new fr.enimaloc.catapult.common.dto.channel.AnkamaData("ACCEPTED", "<b>x</b>", "<i>y</i>"));

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>").doesNotContain("<i>y</i>");
    }

    @Test
    void ankamaUnavailable_hasNoCard() throws Exception {
        String html = ankamaPage(null);

        assertThat(html).doesNotContain("data-conn=\"ankama\"").doesNotContain("ankama-status-body");
    }

    @Test
    void battleNetUnavailable_hasNoCard() throws Exception {
        String html = battleNetPage(null);

        assertThat(html).doesNotContain("data-conn=\"battlenet\"").doesNotContain("battlenet-status-body");
    }

    /** Minecraft unavailable to the channel (catapult-api's null): no card at all. */
    @Test
    void minecraftUnavailable_hasNoCard() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(channelPageData(true, null));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("data-conn=\"minecraft\"").doesNotContain("minecraft-status-body");
    }

    @Test
    void integrationCards_renderEveryIntegrationFromTheirDtos() throws Exception {
        ChannelPageData data = new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null,
                new SteamData(true, true, true, false, true, false, 30),
                new XboxData(false),
                new MinecraftData("ACCEPTED", "Steve123", null),
                new ObsData(true, "localhost", 4455, true),
                TwitchatData.of("beta"),
                null);
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);
        when(apiService.twitchatEvents("enimaloc")).thenReturn(Map.of("STREAM_STARTED",
                List.of(new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"Live\"}", true))));

        String html = mvc.perform(get("/spa/channel/enimaloc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-conn=\"twitch\"", "data-conn=\"steam\"", "data-conn=\"xbox\"",
                "data-conn=\"minecraft\"", "data-conn=\"obs\"", "data-conn=\"twitchat\"");

        // Steam: connected with a token, rate limited but not private.
        assertThat(html).contains("data-if=\"connected,rateLimited\"")
                .contains("data-on=\"connected:SteamConnectionStateEvent.connected,rateLimited:SteamConnectionStateEvent.rateLimited\"");
        assertThat(openingTagForId(html, "steam-token-delete-btn")).doesNotContain("hidden")
                .contains("data-on=\"hasToken:SteamTokenSavedEvent,hasToken:!SteamTokenDeletedEvent\"");
        assertThat(openingTagForId(html, "steam-token-save-btn")).contains("hidden");
        assertThat(openingTagForId(html, "steam-token-shared")).contains("checked")
                .contains("data-value=\"checked:SteamTokenSavedEvent.shared,checked:SteamTokenSharedStateEvent.shared\"");

        // Minecraft: accepted, its name synced from both events.
        assertThat(openingTagForId(html, "minecraft-disconnect-btn")).doesNotContain("hidden")
                .contains("data-on=\"showDisconnect:MinecraftEnrollEvent.status=ACCEPTED,showDisconnect:MinecraftSyncEvent.status=ACCEPTED,showDisconnect:!MinecraftDisconnectedEvent\"");
        assertThat(html).contains(">Steve123<");

        // OBS: connected, password only hinted.
        assertThat(openingTagForId(html, "obs-connect-btn")).contains("hidden");
        assertThat(openingTagForId(html, "obs-disconnect-btn")).doesNotContain("hidden");
        assertThat(openingTagForId(html, "obs-port")).contains("value=\"4455\"").contains("min=\"1\"");
        assertThat(openingTagForId(html, "obs-password")).contains("placeholder=\"••••••••\"");

        // Twitchat: auto plus every supported branch, current one selected.
        assertThat(openingTagForId(html, "twitchat-branch")).contains("value=\"beta\"");
        assertThat(html).contains("value=\"auto\"", "value=\"stable\"");
        assertThat(html).contains("id=\"twitchat-events\"").contains("value=\"NO_GAME_DETECTED\"")
                .contains("class=\"twitchat-payload\"").contains("data-field=\"message\"");
        assertThat(html).doesNotContain("spa:state");
    }
}
