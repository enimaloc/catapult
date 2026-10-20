package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The login, both ends. {@code /auth/login} starts it: a random state kept in the browser's session
 * goes with it to catapult-api's Twitch login ({@code web_state}). {@code /auth/callback} completes it,
 * only for the session that started it ({@code state}, as catapult-api sends it back, matching the one
 * kept, which is used up): a code an attacker got for their own account cannot log a victim into it
 * (login CSRF). It trades the OAuth code for a catapult-api JWT kept in the session, then opens the
 * member's channel. The session then gets a new id (a session id known before the login is worth nothing
 * after it), what was kept for a former login is dropped, and that login's streams close on every instance.
 * The mock profile's codes log into nothing real: its callback takes them without a state.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {
    private final ApiService apiService;
    private final InteractionMetrics metrics;
    private final SseStreams sse;
    private final Environment environment;

    static final String STATE_KEY = "loginState";
    /** How long a login started here may take to come back. */
    static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Starts the login with catapult-api's Twitch login, an invitation's code passed along. */
    @GetMapping("/auth/login")
    public String login(@RequestParam(required = false) String invite, HttpServletRequest request) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        request.getSession().setAttribute(STATE_KEY, state + ":" + Instant.now().toEpochMilli());
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/oauth2/authorization/twitch").queryParam("web_state", state);
        if (invite != null && !invite.isBlank()) target.queryParam("invite", invite);
        return "redirect:" + target.encode().build().toUriString();
    }

    @GetMapping("/auth/callback")
    public String callback(@RequestParam String code, @RequestParam(required = false) String state,
                           HttpServletRequest request) {
        if (!environment.matchesProfiles("mock") && !startedHere(request, state)) {
            log.warn("A login callback came for no login this session started");
            metrics.login(false);
            return "redirect:/login?error";
        }
        TokenResponse jwt = apiService.exchangeCode(code);
        metrics.login(jwt != null);
        if (jwt == null) {
            log.warn("Auth code exchange failed or expired");
            return "redirect:/login?error";
        }
        HttpSession session = request.getSession();
        if (session.getAttribute(ApiClient.SESSION_JWT_KEY) != null) sse.closeSession(session.getId());
        request.changeSessionId();
        SessionChannels.forget(session);
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt.token());
        // A new login ends any impersonation: "Back to admin" mustn't restore an older admin session.
        session.removeAttribute(ImpersonationController.ORIGINAL_JWT_KEY);
        return "redirect:" + home(apiService.channelList());
    }

    /**
     * Whether the session behind {@code request} started a login in the last {@link #STATE_TTL}, its state
     * (used up here, whatever the answer) being {@code state} when catapult-api sends one back.
     */
    private static boolean startedHere(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(STATE_KEY) instanceof String kept)) return false;
        session.removeAttribute(STATE_KEY);
        int separator = kept.lastIndexOf(':');
        if (separator < 0) return false;
        try {
            Instant startedAt = Instant.ofEpochMilli(Long.parseLong(kept.substring(separator + 1)));
            if (Instant.now().isAfter(startedAt.plus(STATE_TTL))) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = kept.substring(0, separator);
        return state == null || MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                state.getBytes(StandardCharsets.US_ASCII));
    }

    /** The member's own channel, else the first they can act on; the landing page without any. */
    static String home(ChannelListResponse list) {
        if (list == null || list.channels().isEmpty()) {
            return "/";
        }
        ChannelDto channel = list.channels().stream()
                .filter(c -> c.twitchId().equals(list.viewerTwitchId()))
                .findFirst()
                .orElse(list.channels().getFirst());
        return "/channel/" + channel.twitchUsername();
    }
}
