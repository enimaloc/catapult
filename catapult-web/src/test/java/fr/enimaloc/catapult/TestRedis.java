package fr.enimaloc.catapult;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * A real Redis for the tests of what goes through its pub/sub (one container for the whole run, reaped
 * with the JVM), and a Redis that is not there, for those of what an instance does without it.
 */
public final class TestRedis {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private TestRedis() {}

    /** A connection to the shared Redis, started on first use. */
    public static synchronized LettuceConnectionFactory up() {
        if (!REDIS.isRunning()) REDIS.start();
        return factory(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    /** A connection to a Redis that does not answer: nothing listens on its port. */
    public static LettuceConnectionFactory down() {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return factory("127.0.0.1", port);
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /** {@code container}, made ready the way its application context would, before its runner starts it. */
    public static RedisMessageListenerContainer prepared(RedisMessageListenerContainer container) {
        container.setMaxSubscriptionRegistrationWaitingTime(2_000);
        container.afterPropertiesSet();
        return container;
    }
}
