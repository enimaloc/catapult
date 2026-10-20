package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import io.sentry.Sentry;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SentryUserInterceptorTest {

    private final ApiService api = mock(ApiService.class);
    private final SentryUserInterceptor interceptor = new SentryUserInterceptor(api);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aMembersRequest_namesThemByIdAlone_untilItIsDone() {
        UUID member = UUID.randomUUID();
        when(api.currentUserId()).thenReturn(member);
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            interceptor.preHandle(request, response, new Object());
            sentry.verify(() -> Sentry.setUser(argThat((User user) -> user != null && member.toString().equals(user.getId())
                    && user.getIpAddress() == null && user.getUsername() == null)));

            interceptor.afterCompletion(request, response, new Object(), null);
            sentry.verify(() -> Sentry.setUser(null));
        }
    }

    @Test
    void aVisitorsRequest_namesNoOne() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            interceptor.preHandle(request, response, new Object());

            sentry.verify(() -> Sentry.setUser(null));
        }
    }
}
