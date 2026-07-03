package fr.enimaloc.catapult.getter;

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
        client = new RealDtddApiClient(restClient, rotator);
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
        assertThat(result.get().get(0).dtddId()).isEqualTo(4521L);

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
}
