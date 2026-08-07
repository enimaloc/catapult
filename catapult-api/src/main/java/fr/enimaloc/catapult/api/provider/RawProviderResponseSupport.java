package fr.enimaloc.catapult.api.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.function.Supplier;

@Slf4j
@Component
public class RawProviderResponseSupport {

    private final ObjectMapper objectMapper;

    public RawProviderResponseSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record RawProviderResponse(int status, String body, String error) {
        public boolean hasError() { return error != null; }
    }

    /** Runs {@code rawBodySupplier} (a raw {@code .retrieve().body(String.class)} call), pretty-prints on success, captures HTTP/network failures instead of throwing. */
    public RawProviderResponse fetch(Supplier<String> rawBodySupplier) {
        try {
            String json = rawBodySupplier.get();
            String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.readTree(json));
            return new RawProviderResponse(200, pretty, null);
        } catch (HttpStatusCodeException e) {
            log.warn("Provider raw fetch failed: {} {}", e.getStatusCode(), e.getMessage());
            return new RawProviderResponse(e.getStatusCode().value(), null, e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Provider raw fetch failed: {}", e.getMessage());
            return new RawProviderResponse(502, null, e.getMessage());
        }
    }
}
