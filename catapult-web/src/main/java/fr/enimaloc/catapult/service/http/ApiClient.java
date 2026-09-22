package fr.enimaloc.catapult.service.http;

import fr.enimaloc.catapult.ws.auth.WsAuthContext;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

@Slf4j
@Component
public class ApiClient implements HttpClient {
    public static final String SESSION_JWT_KEY = "jwt";
    public static final String NOT_STARTED_MESSAGE = "502 Bad Gateway: \"<html><EOL><EOL><head><title>502 Bad Gateway</title></head><EOL><EOL><body><EOL><EOL><center><h1>502 Bad Gateway</h1></center><EOL><EOL><hr><center>openresty</center><EOL><EOL></body><EOL><EOL></html><EOL><EOL>\"";

    private final RestClient client;

    public ApiClient(@Value("${catapult.backend-url") String apiUrl, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(apiUrl)
                .requestInterceptor((request, body, execution) -> {
                    String token = currentJwt();
                    if (token != null) request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode() != HttpStatus.UNAUTHORIZED && !req.getURI().getPath().equals("/api/auth/validate")) {
                        log.warn("API returned {} for {}", res.getStatusCode(), req.getURI());
                    }
                })
                .build();
    }

    @Override
    public <T> T req(HttpMethod method, String path, HttpClient.ResponseType<T> responseType, Locale locale, Object... uriVars) {
        try {
            RestClient.RequestHeadersSpec<?> uri = client.method(method).uri(path, uriVars);
            if (locale != null) uri.header(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag());
            RestClient.ResponseSpec retrieve = uri.retrieve();
            if (responseType.isLeft()) return retrieve.body(responseType.left());
            else return retrieve.body(responseType.right());
        } catch (Exception e) {
            if (!e.getMessage().equals(NOT_STARTED_MESSAGE)) {
                log.warn("{} {} failed: {}", method, path, e.getMessage());
            }
            return null;
        }
    }

    private static String currentJwt() {
        // WS dispatch threads have no HTTP RequestContext; the WS layer stashes
        // the session JWT in WsAuthContext before invoking handlers/dispatcher.
        String wsJwt = WsAuthContext.get();
        if (wsJwt != null) return wsJwt;
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpSession session = attrs.getRequest().getSession(false);
            return session != null ? (String) session.getAttribute(SESSION_JWT_KEY) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
