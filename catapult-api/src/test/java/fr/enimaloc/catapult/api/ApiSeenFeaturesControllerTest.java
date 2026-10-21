package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.feature.SeenFeatureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiSeenFeaturesController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiSeenFeaturesControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SeenFeatureRepository seenFeatureRepository;
    @MockitoBean ApiUserResolver userResolver;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void viewer() {
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userResolver.viewer(any())).thenReturn(user);
    }

    @Test
    void seen_listsTheViewersKeys() throws Exception {
        when(seenFeatureRepository.findFeatureKeysByUserId(userId)).thenReturn(List.of("channel.commands"));

        mvc.perform(get("/api/me/seen-features").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"channel.commands\"]"));
    }

    @Test
    void markSeen_recordsTheKeyForTheViewer() throws Exception {
        mvc.perform(put("/api/me/seen-features/channel.commands").with(csrf())
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isNoContent());

        verify(seenFeatureRepository).insertIfAbsent(eq(userId), eq("channel.commands"), any());
    }

    @Test
    void markSeen_refusesAKeyCatapultWebWouldNeverWrite() throws Exception {
        mvc.perform(put("/api/me/seen-features/Not A Key").with(csrf())
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isBadRequest());

        verify(seenFeatureRepository, never()).insertIfAbsent(any(), anyString(), any());
    }
}
