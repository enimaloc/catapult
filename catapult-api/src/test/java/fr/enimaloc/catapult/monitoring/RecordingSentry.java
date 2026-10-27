package fr.enimaloc.catapult.monitoring;

import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryItemType;
import io.sentry.SentryOptions;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A real Sentry SDK, initialised for a test, whose transport keeps what would have left the process instead of
 * sending it: {@link #items()} is everything catapult-api would have sent to Sentry.
 */
public final class RecordingSentry implements AutoCloseable {

    private final List<SentryEnvelopeItem> items = new CopyOnWriteArrayList<>();
    private SentryOptions options;

    private RecordingSentry() {
    }

    public static RecordingSentry init(Consumer<SentryOptions> configure) {
        RecordingSentry sentry = new RecordingSentry();
        Sentry.init(options -> {
            options.setDsn("https://key@sentry.invalid/1");
            options.setSendClientReports(false);
            options.setLogs(new SentryOptions.Logs());
            options.getLogs().setEnabled(true);
            options.setTransportFactory((o, request) -> sentry.transport());
            configure.accept(options);
            sentry.options = options;
        });
        return sentry;
    }

    /** Everything sent so far (logs and metrics are batched: flushed first). */
    public List<SentryEnvelopeItem> items() {
        Sentry.flush(2000);
        return List.copyOf(items);
    }

    public List<SentryEnvelopeItem> items(SentryItemType type) {
        return items().stream().filter(i -> i.getHeader().getType() == type).toList();
    }

    public SentryOptions options() {
        return options;
    }

    @Override
    public void close() {
        Sentry.close();
    }

    private ITransport transport() {
        return new ITransport() {
            @Override
            public void send(SentryEnvelope envelope, Hint hint) {
                envelope.getItems().forEach(items::add);
            }

            @Override
            public void flush(long timeoutMillis) {
            }

            @Override
            public RateLimiter getRateLimiter() {
                return null;
            }

            @Override
            public void close(boolean isRestarting) {
            }

            @Override
            public void close() {
            }
        };
    }
}
