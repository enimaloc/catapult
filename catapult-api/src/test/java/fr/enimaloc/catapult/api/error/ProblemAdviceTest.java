package fr.enimaloc.catapult.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.notification.TwitchatValidationException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class ProblemAdviceTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new ProblemAdvice())
            .build();

    private final Logger logger = (Logger) LoggerFactory.getLogger(ProblemAdvice.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void rseIsAProblemWithItsGenericCode() throws Exception {
        mvc.perform(get("/api/probe/rse"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.detail").value("Already resolved"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void codeIsATopLevelMember() throws Exception {
        mvc.perform(get("/api/probe/problem"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("minecraft_device_code_refused"))
                .andExpect(jsonPath("$.upstream").value("AADSTS70002"))
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    @Test
    void springErrorsAreInvalidRequests() throws Exception {
        mvc.perform(get("/api/probe/uuid/x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post("/api/probe/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void unexpectedIsLoggedOnceAndHidesItsMessage() throws Exception {
        mvc.perform(get("/api/probe/npe"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.detail").value("Internal error"));

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(NullPointerException.class.getName());
    }

    @Test
    void declaredStatusIsAProblemAndNotLogged() throws Exception {
        mvc.perform(get("/api/probe/declared"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.detail").value("Bad Request"));
        mvc.perform(get("/api/probe/declared-reason"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.detail").value("Code expired"));
        mvc.perform(get("/api/probe/declared-french"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("gone"))
                .andExpect(jsonPath("$.detail").value("Gone"));

        assertThat(logs.list).isEmpty();
    }

    @Test
    void wrappedResponseStatusKeepsItsStatus() throws Exception {
        mvc.perform(get("/api/probe/wrapped"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.detail").value("Already taken"));
        mvc.perform(get("/api/probe/wrapped-problem"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("minecraft_device_code_refused"));

        assertThat(logs.list).isEmpty();
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(get("/api/probe/denied")))
                .isInstanceOf(ServletException.class)
                .cause()
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void committedResponseIsRethrown() {
        assertThatThrownBy(() -> mvc.perform(get("/api/probe/committed")))
                .isInstanceOf(ServletException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void invalidTwitchatPayloadsListEveryError() throws Exception {
        mvc.perform(get("/api/probe/twitchat"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("twitchat_invalid_payload"))
                .andExpect(jsonPath("$.detail").value("1 invalid Twitchat payload field(s)"))
                .andExpect(jsonPath("$.errors[0].index").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("message"))
                .andExpect(jsonPath("$.errors[0].message").value("Required"));
    }

    @Test
    void invalidTwitchatPayloadsOnAFrozenPathAreRethrown() {
        assertThatThrownBy(() -> mvc.perform(get("/api/game/probe/twitchat")))
                .isInstanceOf(ServletException.class)
                .cause()
                .isInstanceOf(TwitchatValidationException.class);
    }

    @Test
    void frozenPathsKeepTheDefaultResolvers() throws Exception {
        MvcResult rse = mvc.perform(get("/api/game/probe/rse"))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(rse.getResponse().getContentAsString()).isEmpty();
        assertThat(rse.getResponse().getErrorMessage()).isEqualTo("Already resolved");

        MvcResult uuid = mvc.perform(get("/api/game/probe/uuid/x"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(uuid.getResponse().getContentAsString()).isEmpty();

        assertThatThrownBy(() -> mvc.perform(get("/api/game/probe/npe")))
                .isInstanceOf(ServletException.class)
                .cause()
                .isInstanceOf(NullPointerException.class);
    }

    record Payload(String name) {}

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class Declared extends RuntimeException {}

    @ResponseStatus(code = HttpStatus.BAD_REQUEST, reason = "Code expired")
    static class DeclaredWithReason extends RuntimeException {}

    @ResponseStatus(code = HttpStatus.GONE, reason = "Code expir\u00e9")
    static class DeclaredInFrench extends RuntimeException {}

    @RestController
    @RequestMapping({"/api/probe", "/api/game/probe"})
    static class Probe {

        @GetMapping("/rse")
        String rse() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already resolved");
        }

        @GetMapping("/twitchat")
        String twitchat() {
            throw new TwitchatValidationException(List.of(new TwitchatValidationError(2, "message", "Required")));
        }

        @GetMapping("/problem")
        String problem() {
            throw new ApiProblem(
                    ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED,
                    "Microsoft refused the device code",
                    Map.of("upstream", "AADSTS70002"));
        }

        @GetMapping("/declared")
        String declared() {
            throw new Declared();
        }

        @GetMapping("/declared-reason")
        String declaredReason() {
            throw new DeclaredWithReason();
        }

        @GetMapping("/declared-french")
        String declaredFrench() {
            throw new DeclaredInFrench();
        }

        @GetMapping("/wrapped")
        String wrapped() {
            throw new IllegalStateException(
                    "wrapper", new ResponseStatusException(HttpStatus.CONFLICT, "Already taken"));
        }

        @GetMapping("/wrapped-problem")
        String wrappedProblem() {
            throw new IllegalStateException(
                    "wrapper",
                    new ApiProblem(ErrorCode.MINECRAFT_DEVICE_CODE_REFUSED, "Microsoft refused the device code"));
        }

        @GetMapping("/npe")
        String npe() {
            String missing = null;
            return missing.trim();
        }

        @GetMapping("/uuid/{id}")
        String uuid(@PathVariable UUID id) {
            return id.toString();
        }

        @PostMapping("/body")
        String body(@RequestBody Payload payload) {
            return payload.name();
        }

        @GetMapping("/denied")
        String denied() {
            throw new AuthorizationDeniedException("Access Denied");
        }

        @GetMapping("/committed")
        void committed(HttpServletResponse response) throws IOException {
            response.getWriter().write("partial");
            response.flushBuffer();
            throw new IllegalStateException("after commit");
        }
    }
}
