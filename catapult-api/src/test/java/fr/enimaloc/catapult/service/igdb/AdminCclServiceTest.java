package fr.enimaloc.catapult.service.igdb;

import fr.enimaloc.catapult.domain.igdb.IgdbRatingDescriptor;
import fr.enimaloc.catapult.domain.twitch.TwitchCclDefinition;
import fr.enimaloc.catapult.repository.igdb.IgdbRatingDescriptorRepository;
import fr.enimaloc.catapult.repository.twitch.TwitchCclDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** AdminCclService: syncing Twitch CCLs and IGDB descriptors, seeding default mappings, admin edits. */
class AdminCclServiceTest {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token?client_id=twitch-id&client_secret=secret&grant_type=client_credentials";
    private static final String CCL_URL = "https://api.twitch.tv/helix/content_classification_labels";
    private static final String AGE_RATINGS_URL = "https://api.igdb.com/v4/age_ratings";

    private final TwitchCclDefinitionRepository ccls = mock(TwitchCclDefinitionRepository.class);
    private final IgdbRatingDescriptorRepository descriptors = mock(IgdbRatingDescriptorRepository.class);
    private final Map<String, TwitchCclDefinition> storedCcls = new HashMap<>();
    private final List<IgdbRatingDescriptor> storedDescriptors = new ArrayList<>();
    private MockRestServiceServer server;
    private AdminCclService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AdminCclService(builder.build(), ccls, descriptors, null);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "twitchClientId", "twitch-id");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "igdbClientId", "");

        when(ccls.findById(anyString())).thenAnswer(call -> Optional.ofNullable(storedCcls.get(call.<String>getArgument(0))));
        when(ccls.findAll()).thenAnswer(call -> List.copyOf(storedCcls.values()));
        when(ccls.save(any())).thenAnswer(call -> {
            TwitchCclDefinition ccl = call.getArgument(0);
            storedCcls.put(ccl.getId(), ccl);
            return ccl;
        });
        when(descriptors.existsById(anyLong())).thenAnswer(call ->
                storedDescriptors.stream().anyMatch(d -> d.getId().equals(call.<Long>getArgument(0))));
        when(descriptors.findAll()).thenAnswer(call -> List.copyOf(storedDescriptors));
        when(descriptors.save(any())).thenAnswer(call -> {
            storedDescriptors.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(descriptors.findById(anyLong())).thenAnswer(call -> storedDescriptors.stream()
                .filter(d -> d.getId().equals(call.<Long>getArgument(0))).findFirst());
        when(descriptors.findByDescription(anyString())).thenAnswer(call -> storedDescriptors.stream()
                .filter(d -> d.getDescription().equals(call.getArgument(0))).toList());
    }

    private void json(String url, HttpMethod httpMethod, String body) {
        server.expect(requestTo(url)).andExpect(method(httpMethod))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static IgdbRatingDescriptor descriptor(long id, String description) {
        IgdbRatingDescriptor descriptor = new IgdbRatingDescriptor();
        descriptor.setId(id);
        descriptor.setDescription(description);
        descriptor.setDisplayName(description);
        return descriptor;
    }

    private static TwitchCclDefinition ccl(String id) {
        TwitchCclDefinition ccl = new TwitchCclDefinition();
        ccl.setId(id);
        return ccl;
    }

    @Nested
    class StartupSync {
        @Test
        void syncsCcls_thenDescriptors_thenSeedsMappingsByKeyword() {
            storedCcls.put("Gambling", ccl("Gambling"));
            json(TOKEN_URL, HttpMethod.POST, "{\"access_token\": \"app-token\"}");
            server.expect(requestTo(CCL_URL))
                    .andExpect(header("Authorization", "Bearer app-token"))
                    .andExpect(header("Client-Id", "twitch-id"))
                    .andRespond(withSuccess("""
                            {"data": [
                              {"id": "MatureGame", "name": "Mature", "description": "skipped"},
                              {"id": "ViolentGraphic", "name": "Violence", "description": "Graphic violence"},
                              {"id": "Gambling", "name": "Gambling", "description": "Betting"},
                              {"id": "PoliticsAndSensitiveSocialIssues", "name": "Politics", "description": "No keywords"}
                            ]}""", MediaType.APPLICATION_JSON));
            server.expect(requestTo(AGE_RATINGS_URL))
                    .andExpect(header("Client-ID", "twitch-id"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("rating_content_descriptions.organization")))
                    .andRespond(withSuccess("""
                            [
                              {"rating_content_descriptions": [
                                {"id": 29, "description": "Violence", "organization": 1},
                                {"id": 50, "description": "Violence", "organization": 2},
                                {"id": 77, "description": "In-game purchases", "organization": 9},
                                {"id": 78, "description": "Mild Lyrics"}
                              ]},
                              {"id": 1},
                              {"rating_content_descriptions": [{"id": 29, "description": "Violence", "organization": 1}]}
                            ]""", MediaType.APPLICATION_JSON));

            service.init();

            server.verify();
            assertThat(storedCcls).containsOnlyKeys("ViolentGraphic", "Gambling", "PoliticsAndSensitiveSocialIssues");
            assertThat(storedCcls.get("ViolentGraphic").getName()).isEqualTo("Violence");
            assertThat(storedCcls.get("Gambling").getDescription()).isEqualTo("Betting");
            assertThat(storedDescriptors).extracting(IgdbRatingDescriptor::getDisplayName)
                    .containsExactly("ESRB — Violence", "PEGI — Violence", "Org9 — In-game purchases", "Unknown — Mild Lyrics");
            assertThat(storedCcls.get("ViolentGraphic").getIgdbMappings()).extracting(IgdbRatingDescriptor::getId)
                    .containsExactlyInAnyOrder(29L, 50L);
            assertThat(storedCcls.get("Gambling").getIgdbMappings()).isEmpty();
            assertThat(storedCcls.get("PoliticsAndSensitiveSocialIssues").getIgdbMappings()).isEmpty();
        }

        @Test
        void igdbClientId_takesPrecedenceOverTheTwitchOne() {
            ReflectionTestUtils.setField(service, "igdbClientId", "igdb-id");
            json(TOKEN_URL, HttpMethod.POST, "{\"access_token\": \"app-token\"}");
            json(CCL_URL, HttpMethod.GET, "{}");
            server.expect(requestTo(AGE_RATINGS_URL)).andExpect(header("Client-ID", "igdb-id"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

            service.refreshFromApi();

            server.verify();
        }

        @Test
        void missingCredentials_skipTheSync() {
            ReflectionTestUtils.setField(service, "twitchClientSecret", "");
            service.init();

            ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
            ReflectionTestUtils.setField(service, "twitchClientId", " ");
            service.init();

            server.verify();
            verify(ccls, never()).save(any());
        }

        @Test
        void tokenWithoutAccessToken_skipsTheSync() {
            json(TOKEN_URL, HttpMethod.POST, "{}");

            service.init();

            server.verify();
            verify(ccls, never()).findAll();
        }

        @Test
        void failures_areSwallowed() {
            server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

            service.init();

            server.verify();
        }

        @Test
        void emptyPayloads_changeNothing() {
            json(TOKEN_URL, HttpMethod.POST, "{\"access_token\": \"app-token\"}");
            json(CCL_URL, HttpMethod.GET, "{\"data\": null}");
            server.expect(requestTo(AGE_RATINGS_URL)).andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

            service.init();

            server.verify();
            verify(ccls, never()).save(any());
            verify(descriptors, never()).save(any());
        }
    }

    @Nested
    class DefaultMappings {
        @Test
        void areNotSeededOnceTheAdminMappedAnything() {
            TwitchCclDefinition violent = ccl("ViolentGraphic");
            TwitchCclDefinition sexual = ccl("SexualThemes");
            sexual.getIgdbMappings().add(descriptor(1, "Nudity"));
            storedCcls.put(violent.getId(), violent);
            storedCcls.put(sexual.getId(), sexual);
            storedDescriptors.add(descriptor(29, "Violence"));

            service.applyDefaultMappings();

            assertThat(violent.getIgdbMappings()).isEmpty();
        }

        @Test
        void needBothCclsAndDescriptors() {
            storedCcls.put("ViolentGraphic", ccl("ViolentGraphic"));
            service.applyDefaultMappings();

            storedCcls.clear();
            storedDescriptors.add(descriptor(29, "Violence"));
            service.applyDefaultMappings();

            verify(ccls, never()).save(any());
        }
    }

    @Nested
    class AdminEdits {
        @Test
        void descriptorList_keepsTheLowestIdPerDescription_sorted() {
            storedDescriptors.addAll(List.of(descriptor(50, "Violence"), descriptor(29, "Violence"),
                    descriptor(3, "Blood"), descriptor(60, "Violence")));

            assertThat(service.getAllIgdbDescriptors()).extracting(IgdbRatingDescriptor::getId).containsExactly(3L, 29L);
        }

        @Test
        void savingMappings_expandsEachRepresentativeToEveryDescriptorWithItsText() {
            storedCcls.put("ViolentGraphic", ccl("ViolentGraphic"));
            storedDescriptors.addAll(List.of(descriptor(29, "Violence"), descriptor(50, "Violence"), descriptor(3, "Blood")));

            service.saveMappings("ViolentGraphic", Set.of(29L, 999L));

            ArgumentCaptor<TwitchCclDefinition> saved = ArgumentCaptor.forClass(TwitchCclDefinition.class);
            verify(ccls).save(saved.capture());
            assertThat(saved.getValue().getIgdbMappings()).extracting(IgdbRatingDescriptor::getId)
                    .containsExactlyInAnyOrder(29L, 50L);
        }

        @Test
        void savingMappingsOfAnUnknownCcl_doesNothing() {
            service.saveMappings("Nope", Set.of(29L));

            verify(ccls, never()).save(any());
        }

        @Test
        void cclList_comesFromTheRepository() {
            storedCcls.put("Gambling", ccl("Gambling"));

            assertThat(service.getAllCcls()).extracting(TwitchCclDefinition::getId).containsExactly("Gambling");
        }
    }
}
