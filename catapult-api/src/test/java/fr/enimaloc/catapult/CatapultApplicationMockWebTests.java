package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.service.twitch.MockTwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("mock-web")
@TestPropertySource(
        properties =
                {
                    "spring.datasource.url=jdbc:h2:mem:catapult_full_context_mock_web;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
                    "spring.data.redis.port=9"
                })
class CatapultApplicationMockWebTests extends FullContextTestBase {

    @Test
    void loadsTheMockWebServletContext() {
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(MockTwitchService.class);
        assertThat(context.containsBean("publicMeApiSecurityFilterChain")).isTrue();
        assertThat(environment.matchesProfiles("mock-steam")).isTrue();
    }

    @Test
    void staysOffTheHostRedis() {
        assertThat(((LettuceConnectionFactory) context.getBean(RedisConnectionFactory.class)).getPort())
                .isEqualTo(9);
    }
}
