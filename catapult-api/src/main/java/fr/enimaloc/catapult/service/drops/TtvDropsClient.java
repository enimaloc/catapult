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
@lombok.extern.slf4j.Slf4j
@Component
public class TtvDropsClient {

    private static final int PAGE_SIZE = 100;
    /** Far above the campaigns that ever run at once: a listing longer than this is the third party misbehaving. */
    private static final int MAX_PAGES = 20;
    // ttvdrops is a third party: a campaign id goes in a request path, its links in the dashboard's pages
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]+");

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
     * Every running campaign, every page of them, the restricted ones with their allowed channels. Throws when a page
     * fails or the listing runs past {@value #MAX_PAGES} pages: a list is never half read.
     */
    public List<DropCampaign> activeCampaigns() {
        List<Item> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES) throw new IllegalStateException("ttvdrops: more than " + MAX_PAGES + " pages");
            int current = page;
            Page answer = observations.observe("ttvdrops", "get_campaigns", () -> restClient.get()
                    .uri(baseUrl + "campaigns/?status=active&page_size=" + PAGE_SIZE + "&page=" + current)
                    .retrieve().body(Page.class));
            if (answer == null || answer.items() == null) throw new IllegalStateException("ttvdrops: no campaigns page");
            items.addAll(answer.items());
            // Done once every campaign is read, whatever page size the server keeps to
            if (answer.items().isEmpty() || items.size() >= answer.total()) break;
        }
        List<DropCampaign> campaigns = new ArrayList<>();
        for (Item item : items) {
            if (!"active".equals(item.status()) || item.game() == null || item.game().twitchId() == null
                    || item.twitchId() == null || !ID.matcher(item.twitchId()).matches()) continue;
            boolean restricted = Boolean.TRUE.equals(item.allowIsEnabled());
            Set<String> allowed = restricted ? allowedChannels(item.twitchId()) : Set.of();
            campaigns.add(new DropCampaign(item.twitchId(), item.name(), item.game().twitchId(),
                    item.game().displayName(), item.startAt(), item.endAt(), web(item.detailsUrl()),
                    image(item.imageUrl()), restricted, allowed));
        }
        return campaigns;
    }

    /**
     * A restricted campaign's channels; none when its details can't be read: then it runs for nobody, rather than
     * the whole refresh failing for one campaign.
     */
    private Set<String> allowedChannels(String campaignId) {
        Detail detail;
        try {
            detail = observations.observe("ttvdrops", "get_campaign", () -> restClient.get()
                    .uri(baseUrl + "campaigns/{id}/", campaignId).retrieve().body(Detail.class));
        } catch (RuntimeException e) {
            log.warn("[Drops] Could not read campaign {}'s channels, it runs for nobody: {}", campaignId, e.getMessage());
            return Set.of();
        }
        if (detail == null || detail.allowedChannels() == null) return Set.of();
        return detail.allowedChannels().stream().map(Channel::twitchId).collect(Collectors.toUnmodifiableSet());
    }

    /** An http(s) link, null otherwise (a javascript: one would run from the dashboard's link). */
    private static String web(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.strip());
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A campaign image, made absolute: only one served by ttvdrops itself, null otherwise. */
    private String image(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            URI base = URI.create(baseUrl);
            URI image = base.resolve(path.strip());
            String absolute = web(image.toString());
            return absolute != null && base.getHost().equalsIgnoreCase(image.getHost()) ? absolute : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
