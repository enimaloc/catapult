package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.controller.ImpersonationController;
import fr.enimaloc.catapult.controller.SessionChannels;
import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.service.http.ApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class SessionLoginTest {

    private final SseStreams sse = mock(SseStreams.class);
    private final SessionLogin login = new SessionLogin(sse);
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    SessionLoginTest() {
        request.setSession(session);
    }

    @Test
    void rotatesTheSessionId() {
        String before = session.getId();

        login.establish(request, "the-jwt");

        assertThat(session.getId()).isNotEqualTo(before);
    }

    @Test
    void storesTheJwt() {
        SessionChannels.remember(session, List.of(new ChannelDto(UUID.randomUUID(), "1", "former", "img", false)));

        login.establish(request, "the-jwt");

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("the-jwt");
        assertThat(session.getAttributeNames().asIterator()).toIterable()
                .containsExactly(ApiClient.SESSION_JWT_KEY);
    }

    @Test
    void dropsTheImpersonationJwt() {
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "member-jwt");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin-jwt");

        login.establish(request, "the-jwt");

        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
    }

    @Test
    void closesThePreviousStream() {
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "former-jwt");
        String before = session.getId();

        login.establish(request, "the-jwt");

        verify(sse).closeSession(before);
    }

    @Test
    void closesNoStreamWithoutAFormerLogin() {
        login.establish(request, "the-jwt");

        verify(sse, never()).closeSession(anyString());
    }
}
