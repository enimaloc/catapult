package fr.enimaloc.catapult.getter.dtdd;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RealDtddApiClientTest {

    @Mock DtddApiKeyRotator rotator;

    MockWebServer server;
    RealDtddApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        ExternalApiObservations apiObservations = new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry());
        client = new RealDtddApiClient(restClient, rotator, apiObservations);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void search_returnsEmpty_whenRotatorHasNoKey() {
        when(rotator.nextKey()).thenReturn(Optional.empty());
        assertThat(client.search("stardew")).isEmpty();
    }

    @Test
    void search_returnsResults_on200() throws Exception {
        when(rotator.nextKey()).thenReturn(Optional.of("KEY_A"));
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                [
                  { "id": 4521, "name": "Stardew Valley", "itemTypeName": "Video Game", "posterUrl": null }
                ]
                """));

        Optional<List<DtddApiClient.DtddSearchResult>> result = client.search("stardew");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().getFirst().dtddId()).isEqualTo(4521L);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("X-API-KEY")).isEqualTo("KEY_A");
        assertThat(req.getPath()).contains("q=stardew");
    }

    @Test
    void search_retriesOnceWithOtherKey_on429() throws Exception {
        when(rotator.nextKey())
            .thenReturn(Optional.of("KEY_A"))
            .thenReturn(Optional.of("KEY_B"));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("[]"));

        Optional<List<DtddApiClient.DtddSearchResult>> result = client.search("stardew");

        verify(rotator).onKeyRateLimited("KEY_A", 60);
        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void search_returnsEmpty_whenSecondAttemptAlso429() throws Exception {
        when(rotator.nextKey())
            .thenReturn(Optional.of("KEY_A"))
            .thenReturn(Optional.of("KEY_B"));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30"));

        Optional<List<DtddApiClient.DtddSearchResult>> result = client.search("stardew");
        assertThat(result).isEmpty();
    }

    @Test
    void fetchTopics_returnsEmpty_on404() {
        when(rotator.nextKey()).thenReturn(Optional.of("KEY_A"));
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(client.fetchTopics(42L)).isEmpty();
    }

    @Test
    void fetchTopics_returnsEmpty_on5xx() {
        when(rotator.nextKey()).thenReturn(Optional.of("KEY_A"));
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThat(client.fetchTopics(42L)).isEmpty();
    }

    @Test
    void fetchTopics_parsesPayload_on200() {
        when(rotator.nextKey()).thenReturn(Optional.of("KEY_A"));
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                { "topicItemStats": [
                    { "topicName": "A dog dies", "yesSum": 5, "noSum": 1 },
                    { "topicName": "Flashing lights", "yesSum": 1, "noSum": 1 },
                    { "topicName": "Bad ending", "yesSum": 0, "noSum": 4 }
                ] }
                """));

        Optional<DtddApiClient.DtddTopics> topics = client.fetchTopics(42L);
        assertThat(topics).isPresent();
        assertThat(topics.get().yesTopics()).containsExactly("A dog dies");
        assertThat(topics.get().mostlyTopics()).containsExactly("Flashing lights");
        assertThat(topics.get().noTopics()).containsExactly("Bad ending");
    }

    /** DTDD's answer as its v3 docs give it since 2026-11: a severity per topic, and fields we don't model. */
    @Test
    void item_readsTheSeverity_andIgnoresUnknownFields() {
        when(rotator.nextKey()).thenReturn(Optional.of("KEY_A"));
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                { "id": 10752, "name": "A game", "somethingNew": { "x": 1 },
                  "topicItemStats": [
                    { "topicItemId": 395866, "yesSum": 57, "noSum": 3, "numComments": 7, "topicId": 153,
                      "topicName": "a dog dies", "itemId": 10752,
                      "severity": { "levels": ["Implied", "Depicted", "Excessive"], "counts": [2, 9, 1],
                                    "percents": [17, 75, 8], "total": 12, "verified": 2 } },
                    { "topicItemId": 395867, "yesSum": 1, "noSum": 4, "numComments": 0, "topicId": 154,
                      "topicName": "a cat dies", "itemId": 10752, "severity": null }
                ] }
                """));

        Optional<DtddApiClient.DtddItem> item = client.item(10752L);

        assertThat(item).isPresent();
        DtddApiClient.DtddTopicItemStat[] stats = item.get().topicItemStats();
        assertThat(stats).hasSize(2);
        DtddApiClient.DtddSeverity severity = stats[0].severity();
        assertThat(severity.levels()).containsExactly("Implied", "Depicted", "Excessive");
        assertThat(severity.counts()).containsExactly(2, 9, 1);
        assertThat(severity.percents()).containsExactly(17, 75, 8);
        assertThat(severity.total()).isEqualTo(12);
        assertThat(severity.verifiedLevel()).contains("Depicted");
        assertThat(stats[1].severity()).isNull();
    }

    @Test
    void verifiedLevel_isEmpty_withoutAReviewersLevelInRange() {
        List<String> levels = List.of("Mild", "Shown", "Severe");
        assertThat(new DtddApiClient.DtddSeverity(levels, List.of(), List.of(), 0, null).verifiedLevel()).isEmpty();
        assertThat(new DtddApiClient.DtddSeverity(levels, List.of(), List.of(), 0, 0).verifiedLevel()).isEmpty();
        assertThat(new DtddApiClient.DtddSeverity(levels, List.of(), List.of(), 0, 4).verifiedLevel()).isEmpty();
        assertThat(new DtddApiClient.DtddSeverity(levels, List.of(), List.of(), 0, 1).verifiedLevel()).contains("Mild");
    }
}
