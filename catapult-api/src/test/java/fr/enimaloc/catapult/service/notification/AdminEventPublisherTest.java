package fr.enimaloc.catapult.service.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminEventPublisherTest {

    private RedisEventPublisher redis;
    private AdminEventPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = mock(RedisEventPublisher.class);
        publisher = new AdminEventPublisher(redis);
    }

    @Test
    void keyAdded_publishesMaskedStatusUnderProviderEvent() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("id", "abcd");
        status.put("masked", "0000…1111");
        status.put("owner", null);
        status.put("blocked", false);
        status.put("blockedForSeconds", 0L);

        publisher.keyAdded(AdminEventPublisher.PROVIDER_STEAM, status);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("steam.key.added"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("key", status));
        // Never a raw key on the wire.
        assertThat(data.getValue().toString()).doesNotContain("apiKey");
    }

    @Test
    void keyDeleted_publishesKeyId() {
        publisher.keyDeleted(AdminEventPublisher.PROVIDER_DTDD, "hash-123");

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("dtdd.key.deleted"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("keyId", "hash-123"));
    }

    @Test
    void keysRefreshed_publishesFullList() {
        List<Object> keys = List.of(Map.of("id", "a"), Map.of("id", "b"));

        publisher.keysRefreshed(AdminEventPublisher.PROVIDER_STEAM, keys);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("steam.keys.refreshed"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("keys", keys));
    }

    @Test
    void twDefinitionAdded_publishesDefinition() {
        Object def = Map.of("id", "gore", "label", "Gore", "enabled", true, "sortOrder", 0);

        publisher.twDefinitionAdded(def);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("tw.definition.added"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("definition", def));
    }

    @Test
    void cclMappingsUpdated_publishesCcl() {
        Object ccl = Map.of("id", "DrugsIntoxication", "name", "Drugs", "mappedDescriptions", List.of("Alcohol"));

        publisher.cclMappingsUpdated(ccl);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("ccl.mappings.updated"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("ccl", ccl));
    }

    @Test
    void cclRefreshed_publishesCclsAndDescriptors() {
        List<Object> ccls = List.of(Map.of("id", "a"));
        List<Object> descriptors = List.of(Map.of("id", 1L, "description", "Violence"));

        publisher.cclRefreshed(ccls, descriptors);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishAdmin(org.mockito.ArgumentMatchers.eq("ccl.refreshed"), data.capture());
        assertThat(data.getValue()).isEqualTo(Map.of("ccls", ccls, "igdbDescriptors", descriptors));
    }
}
