package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto;
import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto.Severity;
import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto.Source;
import fr.enimaloc.catapult.common.dto.channel.TwDetailsDto.Tw;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Why a binding has its TWs, as the TW panel's details dialog renders it. */
@WebMvcTest(controllers = SpaFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class TwDetailsRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    private String render(Locale locale) throws Exception {
        return mvc.perform(get("/spa/channel/bindings/b1/tw-details").locale(locale)
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachTw_isToldWithItsSources_inPlainWords() throws Exception {
        when(apiService.twDetails("b1")).thenReturn(new TwDetailsDto(false, List.of(
                new Tw("gore", "Gore", true, "DETECTED", List.of(
                        new Source("gore", "Gore", "STEAM_CONTENT", "2", null),
                        new Source("gore", "Gore", "STEAM_KEYWORD", "blood", null),
                        new Source("gore", "Gore", "IGDB_DESCRIPTOR", "17", null),
                        new Source("gore", "Gore", "DTDD_TOPIC", "there's blood/gore",
                                new Severity("VOTES", "Depicted", "Excessive", 12, 83)),
                        new Source("gore", "Gore", "DTDD_TOPIC", "a corpse is shown",
                                new Severity("TOO_FEW_VOTES", "Bloody", "Shown", 1, 0)))),
                new Tw("spiders", "Spiders", false, "DETECTED", List.of(
                        new Source("spiders", "Spiders", "DTDD_TOPIC", "there are spiders",
                                new Severity("NO_THRESHOLD", null, null, 0, 0))))),
                List.of(new Source("jumpscares", "Jump scares", "DTDD_TOPIC", "there are jump scares",
                        new Severity("BELOW_VOTES", "Startling", "Mild", 9, 22)))));

        String html = render(Locale.FRENCH);

        assertThat(html).contains(">Gore<", "Steam : violence ou gore fréquents",
                "Steam : la note de contenu mentionne « blood »", "IGDB : descripteur de contenu n°17",
                "DoesTheDogDie : « there&#39;s blood/gore »", "83 % des votes au niveau Depicted ou plus (12 votes).",
                "Pas assez de votes pour juger la gravité (1 vote) : activé par défaut.",
                "désactivé par toi", "Écartés par leur gravité", ">Jump scares<",
                "Seulement 22 % des votes au niveau Startling ou plus (9 votes, surtout Mild).");
        // Without a threshold, nothing about the severity
        String spiders = html.substring(html.indexOf("data-tw=\"spiders\""), html.indexOf("Écartés"));
        assertThat(spiders).doesNotContain("tw-details-severity\">\n        <");
        assertThat(spiders).doesNotContain("% des votes");
    }

    @Test
    void theStreamersAndAnAdminsChoices_andATwWithoutSource_areToldAsSuch() throws Exception {
        when(apiService.twDetails("b1")).thenReturn(new TwDetailsDto(true, List.of(
                new Tw("spiders", "Spiders", true, "STREAMER", List.of()),
                new Tw("gore", "Gore", true, "ADMIN", List.of()),
                new Tw("old", "Old", true, "NO_SOURCE", List.of())), List.of()));

        assertThat(render(Locale.ENGLISH)).contains("You changed this game&#39;s TWs", "Added by you.",
                "Set by a Catapult admin for this game.", "Nothing suggests it any more")
                .doesNotContain("Kept off by their severity");
    }

    @Test
    void detailsUnavailable_saySo() throws Exception {
        assertThat(render(Locale.ENGLISH)).contains("The details aren&#39;t available right now.");
    }
}
