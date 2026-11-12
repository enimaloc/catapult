package fr.enimaloc.catapult.monitoring;

import io.micrometer.common.KeyValue;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;

/**
 * Spring's {@code http.client.requests} tags, the {@code uri} without its query string: many calls build theirs
 * by concatenation ("/helix/channels?broadcaster_id=" + id), which Spring takes as the template, so each
 * streamer made a tag of its own until Micrometer's cap dropped every new uri. Picked up by Spring Boot's
 * RestClient observation customizer in place of its default.
 */
@Component
public class HttpClientUriTagConvention extends DefaultClientRequestObservationConvention {

    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
        KeyValue uri = super.uri(context);
        int query = uri.getValue().indexOf('?');
        return query < 0 ? uri : KeyValue.of(uri.getKey(), uri.getValue().substring(0, query));
    }
}
