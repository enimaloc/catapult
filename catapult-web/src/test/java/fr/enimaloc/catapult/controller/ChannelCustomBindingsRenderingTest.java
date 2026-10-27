package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The custom bindings of the channel page: the overview's list (under the associations, shaped like them) and the
 * Account tab's API token card exist for the owner while the experiment is open to the channel, and nowhere else.
 */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class ChannelCustomBindingsRenderingTest {
    private static final UUID ART = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEV = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @BeforeEach
    void stubSettingsAndDtddMapping() {
        lenient().when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false));
        lenient().when(apiService.dtddMappingStatus(any())).thenReturn(new DtddMappingStatusDto(null, null, false, null));
    }

    private static CustomBindingsStateDto state() {
        return new CustomBindingsStateDto(true, List.of(
                new CustomBindingDto(ART, "Art", "509660", "Art", List.of(), List.of(), false),
                new CustomBindingDto(DEV, "Développement", "1469308723", "Software and Game Development",
                        List.of(), List.of(), true)), ART, Instant.parse("2026-10-24T10:00:00Z"));
    }

    private String render(boolean owner, CustomBindingsStateDto bindings) throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", owner, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, "uuid", bindings));
        return mvc.perform(get("/spa/channel/enimaloc").locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String panel(String html, String tab) {
        int start = html.indexOf("<mdui-tab-panel slot=\"panel\" value=\"" + tab + "\"");
        assertThat(start).as("%s panel", tab).isNotNegative();
        int end = html.indexOf("</mdui-tab-panel>", start);
        return html.substring(start, end);
    }

    private static final Pattern START_TAG = Pattern.compile("<([a-z][a-z0-9-]*)((?:\\s[^>]*)?)>([^<]*)");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z:_-]+)(?:=\"([^\"]*)\")?");

    /** A start tag of the rendered markup: its attributes, whatever their order, and the text right after it. */
    private record Tag(String name, Map<String, String> attributes, String text) {
        String attr(String name) {
            return attributes.get(name);
        }

        Set<String> classes() {
            String value = attributes.getOrDefault("class", "");
            return value.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(value.trim().split("\\s+")));
        }

        boolean hasClass(String name) {
            return classes().contains(name);
        }
    }

    /** The first start tag of {@code html} matching {@code which} (told as {@code what}), else the test fails. */
    private static Tag tag(String html, String what, Predicate<Tag> which) {
        Matcher m = START_TAG.matcher(html);
        while (m.find()) {
            Map<String, String> attributes = new HashMap<>();
            Matcher a = ATTRIBUTE.matcher(m.group(2));
            while (a.find()) attributes.put(a.group(1), a.group(2) == null ? "" : a.group(2));
            Tag tag = new Tag(m.group(1), attributes, m.group(3).trim());
            if (which.test(tag)) return tag;
        }
        throw new AssertionError("no tag with " + what);
    }

    private static Tag byId(String html, String id) {
        return tag(html, "id=\"" + id + "\"", t -> id.equals(t.attr("id")));
    }

    private static Tag byClass(String html, String className) {
        return tag(html, "class " + className, t -> t.hasClass(className));
    }

    @Test
    void ownerWithTheExperimentOpen_getsTheListUnderTheAssociations() throws Exception {
        String overview = panel(render(true, state()), "overview");

        int associations = overview.indexOf("id=\"channel-bindings-list\"");
        int list = overview.indexOf("id=\"channel-custom-bindings-list\"");
        assertThat(associations).isNotNegative();
        assertThat(list).isGreaterThan(associations);
        assertThat(overview).contains("id=\"custom-bindings-error\"", "id=\"custom-binding-delete-dialog\"",
                "Custom bindings");
        Tag newButton = byId(overview, "custom-binding-new-btn");
        assertThat(newButton.name()).isEqualTo("mdui-button");
        assertThat(newButton.text()).isEqualTo("New");
        Tag newRow = byClass(overview, "custom-binding-new");
        assertThat(newRow.classes()).contains("custom-binding-item", "hidden");
        assertThat(newRow.attr("value")).isEqualTo("new");
        assertThat(newRow.attr("data-custom-binding-id")).isNull();
        assertThat(overview).contains("data-custom-binding-id=\"" + ART + "\"", "data-custom-binding-id=\"" + DEV + "\"");
        // Each row's form is pre-filled with the binding, with the category search of the associations.
        String dev = row(overview, DEV);
        assertThat(byClass(dev, "custom-binding-name").attr("value")).isEqualTo("Développement");
        Tag game = byClass(dev, "custom-binding-game-input");
        assertThat(game.classes()).contains("binding-edit-game-input");
        assertThat(game.attr("value")).isEqualTo("Software and Game Development");
        assertThat(byClass(dev, "custom-binding-game-id").attr("value")).isEqualTo("1469308723");
        assertThat(byClass(dev, "custom-binding-uninterruptible").attr("checked")).isNotNull();
        assertThat(byClass(row(overview, ART), "custom-binding-uninterruptible").attr("checked")).isNull();
        assertThat(overview).contains("Activate", "Deactivate", "Save", "Delete");
        assertThat(overview).contains("20 bindings maximum", "Twitch did not apply the category",
                "Another custom binding already has this name");
        // The empty notice is hidden while bindings exist.
        assertThat(byId(overview, "channel-custom-bindings-empty").hasClass("hidden")).isTrue();
    }

    @Test
    void theActiveRowIsMarked_andOnlyTheUninterruptibleOneHasTheChip() throws Exception {
        String overview = panel(render(true, state()), "overview");
        String art = row(overview, ART);
        String dev = row(overview, DEV);

        assertThat(byClass(art, "custom-binding-item").classes()).contains("is-active");
        assertThat(byClass(art, "custom-binding-active-chip").classes()).doesNotContain("hidden");
        assertThat(byClass(art, "custom-binding-active-chip").text()).isEqualTo("Active");
        assertThat(art).doesNotContain("custom-binding-uninterruptible-chip");
        assertThat(byClass(art, "custom-binding-activate").classes()).contains("hidden");
        assertThat(byClass(art, "custom-binding-stop").classes()).doesNotContain("hidden");

        assertThat(byClass(dev, "custom-binding-item").classes()).doesNotContain("is-active");
        assertThat(byClass(dev, "custom-binding-active-chip").classes()).contains("hidden");
        assertThat(byClass(dev, "custom-binding-uninterruptible-chip").text()).isEqualTo("Uninterruptible");
        assertThat(byClass(dev, "custom-binding-activate").classes()).doesNotContain("hidden");
        assertThat(byClass(dev, "custom-binding-stop").classes()).contains("hidden");
    }

    private static String row(String overview, UUID id) {
        int start = overview.lastIndexOf("<mdui-collapse-item", overview.indexOf("data-custom-binding-id=\"" + id + "\""));
        return overview.substring(start, overview.indexOf("</mdui-collapse-item>", start));
    }

    @Test
    void noBindingsYet_showsTheEmptyNoticeAndTheNewRowOnly() throws Exception {
        String overview = panel(render(true, new CustomBindingsStateDto(true, List.of(), null, null)), "overview");

        assertThat(overview).contains("id=\"channel-custom-bindings-list\"", "No custom bindings yet.")
                .doesNotContain("data-custom-binding-id");
        assertThat(byId(overview, "channel-custom-bindings-empty").hasClass("hidden")).isFalse();
    }

    @Test
    void theOldCardsAreGone_andTheTokenCardStaysInTheAccountTab() throws Exception {
        String html = render(true, state());

        assertThat(html).doesNotContain("channel-custom-bindings-card", "channel-custom-bindings-settings",
                "custom-binding-row", "custom-binding-trigger");
        assertThat(panel(html, "settings")).doesNotContain("custom-binding");
        assertThat(panel(html, "account")).contains("id=\"channel-api-token-card\"");
        int token = html.indexOf("id=\"channel-api-token-card\"");
        assertThat(html.indexOf("id=\"channel-api-token-card\"", token + 1)).isNegative();
    }

    @Test
    void experimentClosed_hidesEverything() throws Exception {
        assertThat(render(true, null))
                .doesNotContain("channel-custom-bindings-list").doesNotContain("custom-binding-new-btn")
                .doesNotContain("channel-api-token-card").doesNotContain("custom-binding");
    }

    @Test
    void aModerator_getsNothing() throws Exception {
        assertThat(render(false, null)).doesNotContain("channel-custom-bindings-list").doesNotContain("channel-api-token-card");
    }

    @Test
    void aModeratorIsShownNothing_evenWhenGivenTheBindings() throws Exception {
        assertThat(render(false, state())).doesNotContain("channel-custom-bindings-list");
    }

    @Test
    void frenchTextsAreServed() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null, new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(), null, null, null, null, null, null, null, "uuid", state()));
        String html = mvc.perform(get("/spa/channel/enimaloc").locale(Locale.FRENCH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Reconnecte ton compte Twitch", "Catapult est en pause sur cette chaîne",
                "20 bindings maximum", "Twitch n&#39;a pas appliqué la catégorie", "Nouveau", "Activer",
                "Bindings personnalisés");
    }
}
