package fr.enimaloc.catapult.simulation;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Runs one external call the way its {@link CallSetting} says: really, with a made-up answer, or failing. No real
 * supplier is ever invoked unless the mode is {@code REAL}. Every call is journaled, the real ones too; a
 * {@linkplain SimulatedCalls#polled polled} one only when its answer changed since the account's last one.
 */
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedCallRunner {

    private final SimulationRegistry registry;
    private final SimulationJournal journal;
    /** The last journaled answer of each polled call, by account, key and request. */
    private final Map<String, String> lastPolled = new ConcurrentHashMap<>();

    public SimulatedCallRunner(SimulationRegistry registry, SimulationJournal journal) {
        this.registry = registry;
        this.journal = journal;
    }

    /**
     * @param accountId the account the call acts for, {@code null} for a global call
     * @param request   a short description for the journal
     * @param errorOf   builds the exception the real client throws for an HTTP status
     */
    public <T> T run(@Nullable UUID accountId, CallKey key, String request, Supplier<T> real,
                     Supplier<T> simulated, IntFunction<RuntimeException> errorOf) {
        CallSetting setting = registry.setting(accountId, key);
        sleep(setting.latencyMs());
        switch (setting.mode()) {
            case REAL:
                T answer;
                try {
                    answer = real.get();
                } catch (RuntimeException e) {
                    journal(accountId, key, request, setting, failureOf(e), null);
                    throw e;
                }
                journal(accountId, key, request, setting, "ok", answer);
                return answer;
            case OK:
                T result = simulated.get();
                journal(accountId, key, request, setting, "ok", result);
                return result;
            case ERROR:
                journal(accountId, key, request, setting, "http " + setting.httpStatus(), null);
                throw errorOf.apply(setting.httpStatus());
            case TIMEOUT:
                journal(accountId, key, request, setting, "timeout", null);
                throw new ResourceAccessException("simulated timeout", new SocketTimeoutException("simulated"));
            default:
                throw new IllegalStateException("Unhandled mode " + setting.mode());
        }
    }

    public void runVoid(@Nullable UUID accountId, CallKey key, String request, Runnable real, Runnable simulated,
                        IntFunction<RuntimeException> errorOf) {
        run(accountId, key, request, () -> {
            real.run();
            return null;
        }, () -> {
            simulated.run();
            return null;
        }, errorOf);
    }

    /** What a {@code RestClient} throws for a status: a client error for 4xx, a server error otherwise. */
    public static RuntimeException httpError(int status) {
        HttpStatusCode code = HttpStatusCode.valueOf(status);
        String text = "simulated " + status;
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        return code.is4xxClientError()
                ? HttpClientErrorException.create(code, text, HttpHeaders.EMPTY, body, StandardCharsets.UTF_8)
                : HttpServerErrorException.create(code, text, HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
    }

    private void journal(@Nullable UUID accountId, CallKey key, String request, CallSetting setting, String outcome,
                         @Nullable Object answer) {
        if (SimulatedCalls.polled(key)) {
            String polled = setting.mode() + "|" + outcome + "|" + answer;
            String previous = lastPolled.put(accountId + "|" + key.id() + "|" + request, polled);
            if (Objects.equals(previous, polled)) {
                return;
            }
        }
        journal.record(new SimulationJournal.Entry(Instant.now(), accountId, key.id(), request,
                setting.mode().name(), outcome, setting.latencyMs()));
    }

    /**
     * What made a real call fail, past the exceptions that only carry it ({@code CompletionException}, a wrapper with
     * its cause's message): {@code http <status>} for an HTTP error, as a simulated one reads, else its class name.
     */
    static String failureOf(Throwable e) {
        Throwable failure = e;
        while (failure.getCause() != null && failure.getCause() != failure
                && (failure instanceof CompletionException
                || Objects.equals(failure.getMessage(), failure.getCause().getMessage()))) {
            failure = failure.getCause();
        }
        if (failure instanceof RestClientResponseException http) {
            return "http " + http.getStatusCode().value();
        }
        return failure.getClass().getSimpleName();
    }

    private static void sleep(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating latency", e);
        }
    }
}
