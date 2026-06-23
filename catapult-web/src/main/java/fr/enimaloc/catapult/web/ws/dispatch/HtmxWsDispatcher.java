package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.codec.msg.ResponseMessage;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges {@code action=htmx} WebSocket requests to the regular Spring MVC
 * pipeline. The strategy (validated by the Phase 0 spike) is to fabricate a
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse} pair and
 * invoke {@link DispatcherServlet#service} directly, then capture the rendered
 * Thymeleaf body and HX-* response headers.
 *
 * <p>This is intentionally <strong>not</strong> a {@link RequestHandler}: the
 * outbound response shape (top-level {@code status}/{@code html}/{@code oob})
 * diverges from the standard {@code {result}} envelope, so the hub routes
 * {@code action=htmx} here and serialises the returned {@link ResponseMessage}
 * directly.</p>
 */
@Slf4j
@Component
public class HtmxWsDispatcher {

    public static final String ACTION = "htmx";
    /** Hard ceiling on rendered HTML size — keeps WS frames from exploding. */
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final Pattern OOB_PATTERN = Pattern.compile(
            "<([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*\\bhx-swap-oob=\"([^\"]+)\"[^>]*)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR_PATTERN = Pattern.compile(
            "\\bid=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final DispatcherServlet dispatcherServlet;
    private final ObjectMapper mapper;
    private final ServletContext servletContext;

    @Autowired
    public HtmxWsDispatcher(DispatcherServlet dispatcherServlet, ServletContext servletContext) {
        this(dispatcherServlet, servletContext, JsonMapper.builder().build());
    }

    HtmxWsDispatcher(DispatcherServlet dispatcherServlet, ServletContext servletContext, ObjectMapper mapper) {
        this.dispatcherServlet = dispatcherServlet;
        this.servletContext = servletContext;
        this.mapper = mapper;
    }

    /**
     * Ensures the dispatcher servlet is initialised even when the first request
     * arrives via the WS path (no HTTP request has warmed it up yet). Without
     * this, {@code service()} either NPEs on a null servletConfig or routes to
     * the empty default mappings list.
     */
    @PostConstruct
    public void ensureDispatcherInitialised() {
        try {
            if (dispatcherServlet.getServletConfig() == null) {
                dispatcherServlet.init(new MockServletConfig(servletContext, "dispatcherServlet"));
            }
        } catch (Exception e) {
            log.warn("DispatcherServlet pre-init for HTMX-over-WS failed: {}", e.toString());
        }
    }

    /**
     * Build the request, forward to MVC, extract the response. Always returns
     * a {@link ResponseMessage}; never throws (errors map to {@code ok:false}).
     */
    public ResponseMessage dispatch(String id, WsSession session, Object rawParams) {
        HtmxRequest req = parseRequest(rawParams);
        if (req == null || req.path() == null || req.path().isBlank()) {
            return ResponseMessage.error(id, "INVALID_PARAMS", "path is required");
        }
        if (isMultipart(req)) {
            return ResponseMessage.error(id, "MULTIPART_FALLBACK",
                    "Multipart uploads must use plain HTTP, not WS");
        }

        MockHttpServletRequest httpReq = buildRequest(req);
        MockHttpServletResponse httpResp = new MockHttpServletResponse();

        SecurityContext previousCtx = SecurityContextHolder.getContext();
        installSecurityContext(session);
        try {
            dispatcherServlet.service(httpReq, httpResp);
        } catch (Exception e) {
            int forbiddenStatus = forbiddenStatusOrZero(e);
            if (forbiddenStatus != 0) {
                return ResponseMessage.htmx(id, forbiddenStatus, null, null, "", null, null);
            }
            log.warn("htmx dispatch {} {} failed: {}", req.method(), req.path(), e.toString());
            return ResponseMessage.error(id, "INTERNAL_ERROR", "Server error");
        } finally {
            SecurityContextHolder.setContext(previousCtx);
        }

        String html;
        try {
            html = httpResp.getContentAsString();
        } catch (Exception e) {
            log.warn("htmx response decode {} failed: {}", req.path(), e.toString());
            return ResponseMessage.error(id, "INTERNAL_ERROR", "Response decode failed");
        }
        if (html != null && html.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return ResponseMessage.error(id, "HTMX_RESPONSE_TOO_LARGE",
                    "HTMX response exceeded " + MAX_RESPONSE_BYTES + " bytes");
        }

        String target = httpResp.getHeader("HX-Retarget");
        String swap = httpResp.getHeader("HX-Reswap");
        Map<String, Object> triggers = parseTriggers(httpResp.getHeader("HX-Trigger"));
        List<ResponseMessage.OobSwap> oob = extractOob(html);

        return ResponseMessage.htmx(id, httpResp.getStatus(), target, swap,
                html == null ? "" : html, oob.isEmpty() ? null : oob, triggers);
    }

    /** Test seam — exposed package-private so tests can poke individual helpers. */
    record HtmxRequest(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, Object> params,
            String csrfToken
    ) {
    }

    private HtmxRequest parseRequest(Object raw) {
        if (raw == null) return null;
        try {
            return mapper.convertValue(raw, HtmxRequest.class);
        } catch (Exception e) {
            log.debug("htmx params not parseable: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isMultipart(HtmxRequest req) {
        if (req.headers() == null) return false;
        for (var e : req.headers().entrySet()) {
            if ("content-type".equalsIgnoreCase(e.getKey())
                    && e.getValue() != null
                    && e.getValue().toLowerCase().contains("multipart/")) {
                return true;
            }
        }
        return false;
    }

    private MockHttpServletRequest buildRequest(HtmxRequest req) {
        String method = req.method() == null ? "GET" : req.method().toUpperCase();
        String path = req.path();
        MockHttpServletRequest httpReq = new MockHttpServletRequest(method, path);
        httpReq.setServletPath(path);
        httpReq.setRequestURI(path);

        // Headers (HX-*, Content-Type, etc.)
        if (req.headers() != null) {
            for (var e : req.headers().entrySet()) {
                if (e.getValue() != null) {
                    httpReq.addHeader(e.getKey(), e.getValue());
                }
            }
        }

        // Parameters: scalar map → query params for GET, form params for POST/PUT/DELETE.
        // Keep request bodies parameter-shaped (form-encoded) since multipart is rejected upstream.
        if (req.params() != null) {
            for (var e : req.params().entrySet()) {
                if (e.getValue() == null) continue;
                if (e.getValue() instanceof Iterable<?> it) {
                    List<String> vals = new ArrayList<>();
                    for (Object o : it) {
                        if (o != null) vals.add(String.valueOf(o));
                    }
                    httpReq.setParameter(e.getKey(), vals.toArray(new String[0]));
                } else {
                    httpReq.setParameter(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }

        // CSRF: install the token in the request attribute Spring Security looks up
        // via HttpSessionCsrfTokenRepository / CsrfTokenRequestAttributeHandler.
        // The attribute key CsrfToken.class.getName() is the contract observed by
        // Spring Security's CsrfFilter for resolving the expected token.
        if (req.csrfToken() != null && !req.csrfToken().isBlank()) {
            CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", req.csrfToken());
            httpReq.setAttribute(CsrfToken.class.getName(), token);
            httpReq.setAttribute("_csrf", token);
            // Also surface as a header so server-side checks that read a header pass.
            httpReq.addHeader("X-CSRF-TOKEN", req.csrfToken());
        }
        return httpReq;
    }

    /**
     * Translates Spring Security access/authentication exceptions raised during
     * controller invocation into the HTTP status the equivalent HTTP request
     * would have produced. Returns 0 when the exception is unrelated.
     */
    private static int forbiddenStatusOrZero(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof org.springframework.security.access.AccessDeniedException) return 403;
            if (cur instanceof org.springframework.security.authentication.AuthenticationCredentialsNotFoundException) {
                return 403;
            }
            if (cur instanceof org.springframework.security.core.AuthenticationException) return 401;
            cur = cur.getCause();
        }
        return 0;
    }

    private void installSecurityContext(WsSession session) {
        SecurityContext ctx = new SecurityContextImpl();
        if (session == null || session.userId().isEmpty()) {
            // Mirror Spring Security's AnonymousAuthenticationFilter so @PreAuthorize
            // checks against role-based rules produce AccessDenied (HTTP 403) instead
            // of AuthenticationCredentialsNotFoundException (HTTP 500-equivalent).
            ctx.setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                    "ws-anon",
                    "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            SecurityContextHolder.setContext(ctx);
            return;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String role : session.roles()) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        Authentication auth = new UsernamePasswordAuthenticationToken(
                session.userId().get(), null, authorities);
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private Map<String, Object> parseTriggers(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        // HX-Trigger may be a simple event name or a JSON object.
        if (headerValue.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(headerValue, Map.class);
                return parsed;
            } catch (Exception ignore) {
                /* fall through to plain-event form */
            }
        }
        Map<String, Object> single = new HashMap<>();
        single.put(headerValue, Map.of());
        return single;
    }

    /**
     * Walks the response HTML and lifts every {@code hx-swap-oob} fragment into
     * a structured {@link ResponseMessage.OobSwap}. The remainder of the HTML
     * (the main fragment) is left untouched and returned as-is — the spec lets
     * the client apply the main body via its declared {@code hx-target}.
     */
    private List<ResponseMessage.OobSwap> extractOob(String html) {
        List<ResponseMessage.OobSwap> result = new ArrayList<>();
        if (html == null || html.isEmpty()) return result;
        Matcher m = OOB_PATTERN.matcher(html);
        while (m.find()) {
            String tag = m.group(1);
            String attrs = m.group(2);
            String oobValue = m.group(3);
            String swapStyle = "outerHTML";
            String target = null;
            if (!"true".equalsIgnoreCase(oobValue) && !oobValue.isBlank()) {
                int colon = oobValue.indexOf(':');
                if (colon < 0) {
                    swapStyle = oobValue;
                } else {
                    swapStyle = oobValue.substring(0, colon);
                    target = oobValue.substring(colon + 1);
                }
            }
            if (target == null) {
                Matcher idM = ID_ATTR_PATTERN.matcher(attrs);
                if (idM.find()) target = "#" + idM.group(1);
            }
            int blockStart = m.start();
            int blockEnd = findMatchingClose(html, m.end(), tag);
            String fragment = blockEnd < 0 ? html.substring(blockStart) : html.substring(blockStart, blockEnd);
            result.add(new ResponseMessage.OobSwap(target, swapStyle, fragment));
        }
        return result;
    }

    /**
     * Naive close-tag finder that handles nested same-name tags. Good enough
     * for HTMX fragments which are not arbitrary HTML documents.
     */
    private static int findMatchingClose(String html, int from, String tag) {
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int depth = 1;
        int idx = from;
        while (idx < html.length()) {
            int nextClose = indexOfIgnoreCase(html, close, idx);
            int nextOpen = indexOfIgnoreCase(html, open, idx);
            if (nextClose < 0) return -1;
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                idx = nextOpen + open.length();
                continue;
            }
            depth--;
            if (depth == 0) return nextClose + close.length();
            idx = nextClose + close.length();
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String hay, String needle, int from) {
        int max = hay.length() - needle.length();
        outer:
        for (int i = from; i <= max; i++) {
            for (int j = 0; j < needle.length(); j++) {
                if (Character.toLowerCase(hay.charAt(i + j)) != Character.toLowerCase(needle.charAt(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
