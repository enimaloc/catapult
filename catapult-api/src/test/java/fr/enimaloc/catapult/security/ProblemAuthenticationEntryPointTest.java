package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

class ProblemAuthenticationEntryPointTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/flags");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private void commence(AuthenticationEntryPoint delegate) throws Exception {
        new ProblemAuthenticationEntryPoint(delegate, json)
                .commence(request, response, new BadCredentialsException("nope"));
    }

    @Test
    void anUnauthorizedAnswerSaysAuthenticationRequired() throws Exception {
        commence((req, res, ex) -> res.setStatus(401));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).get("detail").asString())
                .isEqualTo("Authentication required");
    }

    @Test
    void aBadRequestAnswerSaysInvalidRequest() throws Exception {
        commence((req, res, ex) -> res.setStatus(400));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.readTree(response.getContentAsString()).get("detail").asString())
                .isEqualTo("Invalid request");
    }

    @Test
    void aCommittedResponseIsLeftAlone() throws Exception {
        commence((req, res, ex) -> {
            res.setStatus(401);
            res.getWriter().write("delegate");
            res.flushBuffer();
        });

        assertThat(response.getContentAsString()).isEqualTo("delegate");
    }
}
