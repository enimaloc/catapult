package fr.enimaloc.catapult.service.drops;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The running Twitch drops campaigns, from ttvdrops ({@code drops.base-url}). */
@Component
public class TtvDropsClient {

    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final ExternalApiObservations observations;
    private final String baseUrl;

    public TtvDropsClient(RestClient restClient, ExternalApiObservations observations,
                          @Value("${drops.base-url:https://ttvdrops.lovinator.space/api/v1/twitch/}") String baseUrl) {
        this.restClient = restClient;
        this.observations = observations;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * Every running campaign, every page of them, the restricted ones with their allowed channels. Throws on any
     * failure: a list is never half read.
     */
    public List<DropCampaign> activeCampaigns() {
        List<Item> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            int current = page;
            Page answer = observations.observe("ttvdrops", "get_campaigns", () -> restClient.get()
                    .uri(baseUrl + "campaigns/?status=active&page_size=" + PAGE_SIZE + "&page=" + current)
                    .retrieve().body(Page.class));
            if (answer == null || answer.items() == null) throw new IllegalStateException("ttvdrops: no campaigns page");
            items.addAll(answer.items());
            if (answer.items().isEmpty() || (long) current * PAGE_SIZE >= answer.total()) break;
        }
        List<DropCampaign> campaigns = new ArrayList<>();
        for (Item item : items) {
            if (!"active".equals(item.status()) || item.game() == null || item.game().twitchId() == null) continue;
            boolean restricted = Boolean.TRUE.equals(item.allowIsEnabled());
            Set<String> allowed = restricted ? allowedChannels(item.twitchId()) : Set.of();
            campaigns.add(new DropCampaign(item.twitchId(), item.name(), item.game().twitchId(),
                    item.game().displayName(), item.startAt(), item.endAt(), item.detailsUrl(),
                    absolute(item.imageUrl()), restricted, allowed));
        }
        return campaigns;
    }

    private Set<String> allowedChannels(String campaignId) {
        Detail detail = observations.observe("ttvdrops", "get_campaign", () -> restClient.get()
                .uri(baseUrl + "campaigns/" + campaignId + "/").retrieve().body(Detail.class));
        if (detail == null || detail.allowedChannels() == null) return Set.of();
        return detail.allowedChannels().stream().map(Channel::twitchId).collect(Collectors.toUnmodifiableSet());
    }

    private String absolute(String path) {
        if (path == null || path.isBlank()) return null;
        return URI.create(baseUrl).resolve(path).toString();
    }

    record Page(long total, List<Item> items) {}

    record Item(@JsonProperty("twitch_id") String twitchId, String name, String status,
                @JsonProperty("image_url") String imageUrl, @JsonProperty("details_url") String detailsUrl,
                @JsonProperty("start_at") Instant startAt, @JsonProperty("end_at") Instant endAt,
                @JsonProperty("allow_is_enabled") Boolean allowIsEnabled, Game game) {}

    record Game(@JsonProperty("twitch_id") String twitchId, @JsonProperty("display_name") String displayName) {}

    record Detail(@JsonProperty("allowed_channels") List<Channel> allowedChannels) {}

    record Channel(@JsonProperty("twitch_id") String twitchId) {}
}
