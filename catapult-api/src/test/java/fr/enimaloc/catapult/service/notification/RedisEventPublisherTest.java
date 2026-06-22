package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catapult_redis_pub_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE")
@Testcontainers
class RedisEventPublisherTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @MockitoBean
    AdminCclService adminCclService;

    @MockitoBean
    TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired
    RedisEventPublisher publisher;

    @Autowired
    StringRedisTemplate redisTemplate;

    private BlockingQueue<String> subscribeAndCollect(String channel) throws InterruptedException {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisTemplate.getRequiredConnectionFactory());
        MessageListener listener = (Message message, byte[] pattern) ->
                received.add(new String(message.getBody()));
        container.addMessageListener(listener, new ChannelTopic(channel));
        container.afterPropertiesSet();
        container.start();
        // small wait for subscription to be active
        Thread.sleep(200);
        return received;
    }

    @Test
    void publishGlobal_emits_envelope_to_global_channel() throws Exception {
        BlockingQueue<String> received = subscribeAndCollect(RedisEventPublisher.CHANNEL_GLOBAL);

        publisher.publishGlobal("test.global", Map.of("hello", "world"));

        String payload = received.poll(3, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload)
                .contains("\"name\":\"test.global\"")
                .contains("\"data\":{")
                .contains("\"hello\":\"world\"")
                .contains("\"ts\":");
    }

    @Test
    void publishUser_emits_envelope_to_user_channel() throws Exception {
        UUID userId = UUID.randomUUID();
        BlockingQueue<String> received = subscribeAndCollect(RedisEventPublisher.CHANNEL_USER_PREFIX + userId);

        publisher.publishUser(userId, "notification.created", Map.of("id", "n-1"));

        String payload = received.poll(3, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload)
                .contains("\"name\":\"notification.created\"")
                .contains("\"id\":\"n-1\"");
    }

    @Test
    void publishAdmin_emits_envelope_to_admin_channel() throws Exception {
        BlockingQueue<String> received = subscribeAndCollect(RedisEventPublisher.CHANNEL_ADMIN);

        publisher.publishAdmin("alert.warning", Map.of("title", "high cpu"));

        String payload = received.poll(3, TimeUnit.SECONDS);
        assertThat(payload).isNotNull();
        assertThat(payload)
                .contains("\"name\":\"alert.warning\"")
                .contains("\"title\":\"high cpu\"");
    }
}
