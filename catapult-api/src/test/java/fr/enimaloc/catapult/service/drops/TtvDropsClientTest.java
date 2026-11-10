package fr.enimaloc.catapult.service.drops;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** TtvDropsClient against a mocked ttvdrops: every page of the running campaigns, the restricted ones' channels. */
class TtvDropsClientTest {

    private static final String BASE = "https://ttvdrops.example/api/v1/twitch/";
    private MockRestServiceServer ttv;
    private TtvDropsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        ttv = MockRestServiceServer.bindTo(builder).build();
        client = new TtvDropsClient(builder.build(),
                new ExternalApiObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry()), BASE);
    }

    private static String campaign(String id, String gameId, boolean restricted) {
        return """
                {"twitch_id":"%s","name":"Campaign %s","status":"active","image_url":"/media/campaigns/%s.png",
                 "details_url":"https://example.test/%s","start_at":"2026-11-10T02:00:00Z",
                 "end_at":"2026-11-25T23:59:59.999Z","allow_is_enabled":%s,
                 "game":{"twitch_id":"%s","display_name":"Game %s","name":""}}"""
                .formatted(id, id, id, id, restricted, gameId, gameId);
    }

    private void page(int page, int total, String... items) {
        ttv.expect(once(), requestTo(BASE + "campaigns/?status=active&page_size=100&page=" + page)).andRespond(withSuccess(
                "{\"total\":%d,\"page\":%d,\"page_size\":100,\"items\":[%s]}".formatted(total, page, String.join(",", items)),
                MediaType.APPLICATION_JSON));
    }

    @Test
    void parsesPagesAndRestrictedDetails() {
        page(1, 2, campaign("a", "1", false));
        page(2, 2, campaign("b", "2", true));
        ttv.expect(once(), requestTo(BASE + "campaigns/b/")).andRespond(withSuccess("""
                {"twitch_id":"b","allowed_channels":[{"twitch_id":"42","name":"x"},{"twitch_id":"43","name":"y"}]}""",
                MediaType.APPLICATION_JSON));

        List<DropCampaign> campaigns = client.activeCampaigns();

        assertThat(campaigns).containsExactly(
                new DropCampaign("a", "Campaign a", "1", "Game 1", Instant.parse("2026-11-10T02:00:00Z"),
                        Instant.parse("2026-11-25T23:59:59.999Z"), "https://example.test/a",
                        "https://ttvdrops.example/media/campaigns/a.png", false, Set.of()),
                new DropCampaign("b", "Campaign b", "2", "Game 2", Instant.parse("2026-11-10T02:00:00Z"),
                        Instant.parse("2026-11-25T23:59:59.999Z"), "https://example.test/b",
                        "https://ttvdrops.example/media/campaigns/b.png", true, Set.of("42", "43")));
        ttv.verify();
    }

    @Test
    void failedSecondPage_throws() {
        page(1, 101, campaign("a", "1", false));
        ttv.expect(once(), requestTo(BASE + "campaigns/?status=active&page_size=100&page=2")).andRespond(withServerError());

        assertThatThrownBy(client::activeCampaigns).isInstanceOf(RuntimeException.class);
    }

    @Test
    void untrustedUrls_andIds_areDropped() {
        page(1, 2, """
                {"twitch_id":"a","name":"A","status":"active","image_url":"javascript:alert(1)",
                 "details_url":"javascript:alert(1)","start_at":"2026-11-10T02:00:00Z","end_at":"2026-11-25T23:59:59Z",
                 "allow_is_enabled":false,"game":{"twitch_id":"1","display_name":"G"}}""",
                """
                {"twitch_id":"../../admin","name":"B","status":"active","image_url":"//evil.example/x.png",
                 "details_url":"https://ok.example/b","start_at":"2026-11-10T02:00:00Z","end_at":"2026-11-25T23:59:59Z",
                 "allow_is_enabled":true,"game":{"twitch_id":"2","display_name":"G"}}""");

        List<DropCampaign> campaigns = client.activeCampaigns();

        assertThat(campaigns).extracting(DropCampaign::id).containsExactly("a");
        assertThat(campaigns.getFirst().detailsUrl()).isNull();
        assertThat(campaigns.getFirst().imageUrl()).isNull();
        ttv.verify();
    }

    @Test
    void imageUrl_onlyOnTtvdropsItself() {
        page(1, 1, campaign("a", "1", false).replace("/media/campaigns/a.png", "https://elsewhere.example/a.png"));

        assertThat(client.activeCampaigns().getFirst().imageUrl()).isNull();
    }

    @Test
    void aFailingCampaignDetail_onlyLeavesThatRestrictedCampaignForNobody() {
        page(1, 2, campaign("a", "1", false), campaign("b", "2", true));
        ttv.expect(once(), requestTo(BASE + "campaigns/b/")).andRespond(withServerError());

        List<DropCampaign> campaigns = client.activeCampaigns();

        assertThat(campaigns).extracting(DropCampaign::id).containsExactly("a", "b");
        assertThat(campaigns.get(1).restricted()).isTrue();
        assertThat(campaigns.get(1).allowedChannelIds()).isEmpty();
    }

    @Test
    void stopsOnceEveryCampaignIsRead_evenWhenPagesAreSmallerThanAsked() {
        page(1, 2, campaign("a", "1", false));
        page(2, 2, campaign("b", "2", false));

        assertThat(client.activeCampaigns()).extracting(DropCampaign::id).containsExactly("a", "b");
        ttv.verify();
    }

    @Test
    void anEndlessListing_isCutAtTwentyPages_andFails() {
        for (int p = 1; p <= 20; p++) page(p, 1_000_000, campaign("p" + p, "1", false));

        assertThatThrownBy(client::activeCampaigns).isInstanceOf(IllegalStateException.class);
    }
}
