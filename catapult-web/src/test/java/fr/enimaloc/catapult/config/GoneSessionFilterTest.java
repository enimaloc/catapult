package fr.enimaloc.catapult.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoneSessionFilterTest {
    private final GoneSessionFilter filter = new GoneSessionFilter();

    private static MockHttpServletRequest request(DispatcherType type) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/events/nav");
        request.setDispatcherType(type);
        return request;
    }

    private void run(DispatcherType type, String message) throws Exception {
        filter.doFilter(request(type), new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException(message);
        });
    }

    @Test
    void aStreamsDispatch_endsQuietly_whenItsSessionIsGone() throws Exception {
        run(DispatcherType.ASYNC, GoneSessionFilter.SESSION_GONE);
    }

    /** Through the real filter, as Redis's repository answers for a session it no longer holds. */
    @Test
    void aStreamsDispatch_endsQuietly_whenTheSessionFilterCannotSave() throws Exception {
        MapSessionRepository repository = new MapSessionRepository(new HashMap<>()) {
            @Override
            public void save(MapSession session) {
                throw new IllegalStateException(GoneSessionFilter.SESSION_GONE);
            }
        };
        MockHttpServletRequest request = request(DispatcherType.ASYNC);
        SessionRepositoryFilter<MapSession> sessions = new SessionRepositoryFilter<>(repository);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                req.getSession(true);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(servlet, sessions));
    }

    @Test
    void aPlainRequest_stillFails() {
        assertThatThrownBy(() -> run(DispatcherType.REQUEST, GoneSessionFilter.SESSION_GONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anyOtherFailure_stillFails() {
        assertThatThrownBy(() -> run(DispatcherType.ASYNC, "something else"))
                .isInstanceOf(IllegalStateException.class).hasMessage("something else");
    }

    @Test
    void itRunsAroundTheSessionFilter() {
        assertThat(GoneSessionFilter.class.getAnnotation(Order.class).value())
                .isLessThan(SessionRepositoryFilter.DEFAULT_ORDER);
    }
}
