package fr.enimaloc.catapult.service.enrichment.trailer;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.enrichment.ClientKeys;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remuxes a trailer's HLS H.264 stream into a fragmented MP4 written straight to the caller, with ffmpeg and no
 * re-encoding ({@code -c copy}); nothing is stored. At most {@code catapult.enrichment.ffmpeg-max-processes} run at
 * once (a {@link TrailerBusyException} beyond), each for at most {@code catapult.enrichment.ffmpeg-max-duration};
 * ffmpeg is killed as soon as the caller goes away or the time is up.
 *
 * <p>The public routes ({@link #reservePublic}) never take the last slot, kept for the authenticated route an OBS
 * media source plays, and take at most {@value #MAX_PUBLIC_PER_IP} at once per IP: an anonymous caller cannot hold
 * every slot. With a single slot, public streams are never served.
 */
@Slf4j
@Component
public class FfmpegTrailerStreamer {

    /** The protocols ffmpeg may open for the playlist, its segments and their keys: TLS only, no file, no concat. */
    static final String PROTOCOLS = "https,tls,tcp,crypto";
    /** How long a caller turned away is told to wait. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);
    /** How long ffmpeg may take to exit once its output is over. */
    private static final Duration EXIT_GRACE = Duration.ofSeconds(5);
    /** How much of ffmpeg's error output is kept for the log. */
    private static final int STDERR_TAIL = 4096;
    private static final int BUFFER = 64 * 1024;
    /** Public streams one IP may run at once. */
    static final int MAX_PUBLIC_PER_IP = 1;

    /** Starts a process: {@link ProcessBuilder} in production, a fake one in tests. */
    @FunctionalInterface
    public interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    /** One taken ffmpeg slot, given back on {@link #close()}; closing it twice gives it back once. */
    public interface Reservation extends AutoCloseable {

        /**
         * Remuxes {@code hls} into {@code out} until ffmpeg is done, the caller is gone or the time is up.
         *
         * @throws IllegalArgumentException when {@code hls} is not a stream {@link TrailerHlsUrls} allows
         * @throws IOException              when ffmpeg cannot be started
         */
        void stream(URI hls, OutputStream out) throws IOException;

        @Override
        void close();
    }

    private final ProcessLauncher launcher;
    private final Semaphore permits;
    /** The slots public callers may take: all but one. */
    private final Semaphore publicPermits;
    /** Public streams running per IP; an IP leaves the map when its last one ends. */
    private final ConcurrentHashMap<String, Integer> publicByIp = new ConcurrentHashMap<>();
    private final Duration maxDuration;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ffmpeg-watchdog").factory());
    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public FfmpegTrailerStreamer(EnrichmentProperties properties) {
        this(properties, command -> new ProcessBuilder(command).start());
    }

    FfmpegTrailerStreamer(EnrichmentProperties properties, ProcessLauncher launcher) {
        this.launcher    = launcher;
        this.permits       = new Semaphore(properties.ffmpegMaxProcesses());
        this.publicPermits = new Semaphore(Math.max(0, properties.ffmpegMaxProcesses() - 1));
        this.maxDuration = properties.ffmpegMaxDuration();
    }

    /**
     * Takes an ffmpeg slot without waiting, so the caller can still answer {@code 503} before its response starts.
     *
     * @throws TrailerBusyException when every slot is taken
     */
    public Reservation reserve() {
        if (!permits.tryAcquire()) {
            throw new TrailerBusyException(RETRY_AFTER);
        }
        return new Slot(null);
    }

    /**
     * {@link #reserve()} for a public caller: never the last free slot, and at most {@value #MAX_PUBLIC_PER_IP} at
     * once for {@code ip}.
     *
     * @throws TrailerBusyException when no public slot is free, or {@code ip} already streams
     */
    public Reservation reservePublic(String address) {
        // an IPv6 client counts by its whole /64, which it could otherwise rotate through
        String ip = ClientKeys.of(address);
        boolean[] taken = {false};
        publicByIp.compute(ip, (key, running) -> {
            int count = running == null ? 0 : running;
            if (count >= MAX_PUBLIC_PER_IP) {
                return running;
            }
            taken[0] = true;
            return count + 1;
        });
        if (!taken[0]) {
            throw new TrailerBusyException(RETRY_AFTER);
        }
        if (!publicPermits.tryAcquire()) {
            releaseIp(ip);
            throw new TrailerBusyException(RETRY_AFTER);
        }
        if (!permits.tryAcquire()) {
            publicPermits.release();
            releaseIp(ip);
            throw new TrailerBusyException(RETRY_AFTER);
        }
        return new Slot(ip);
    }

    private void releaseIp(String ip) {
        publicByIp.computeIfPresent(ip, (key, running) -> running <= 1 ? null : running - 1);
    }

    /**
     * {@link #reserve()} then {@link Reservation#stream}, the slot given back at the end.
     *
     * @throws TrailerBusyException when every slot is taken
     */
    public void stream(URI hls, OutputStream out) throws IOException {
        try (Reservation reservation = reserve()) {
            reservation.stream(hls, out);
        }
    }

    /** The longest one remux may run. */
    public Duration maxDuration() {
        return maxDuration;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    /** Number of IPs with a public stream running. */
    int trackedIps() {
        return publicByIp.size();
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        running.forEach(Process::destroyForcibly);
    }

    static List<String> command(URI hls) {
        return List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", PROTOCOLS,
                "-user_agent", EnrichmentUserAgent.VALUE,
                "-i", hls.toString(),
                "-c", "copy", "-movflags", "frag_keyframe+empty_moov", "-f", "mp4", "pipe:1");
    }

    private final class Slot implements Reservation {

        private final AtomicBoolean released = new AtomicBoolean();
        /** The public caller's IP; null for the authenticated route. */
        private final String publicIp;
        /** The ffmpeg this slot runs, killed by {@link #close()} before the slot is given back. */
        private volatile Process current;

        Slot(String publicIp) {
            this.publicIp = publicIp;
        }

        @Override
        public void stream(URI hls, OutputStream out) throws IOException {
            if (!TrailerHlsUrls.isAllowed(hls)) {
                throw new IllegalArgumentException("Not an allowed trailer stream: " + hls);
            }
            Process process = launcher.start(command(hls));
            current = process;
            if (released.get()) {
                // closed while ffmpeg was starting: the slot is no longer ours
                process.destroyForcibly();
                throw new IOException("Trailer slot released before ffmpeg started");
            }
            running.add(process);
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> deadline;
            try {
                deadline = watchdog.schedule(() -> {
                    timedOut.set(true);
                    process.destroyForcibly();
                }, maxDuration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // shutting down: no watchdog could ever stop this ffmpeg, so it must not run
                process.destroyForcibly();
                running.remove(process);
                throw new IOException("Trailer streaming is shutting down", e);
            }
            StderrTail stderr = new StderrTail(process.getErrorStream());
            boolean clientGone = false;
            try {
                closeQuietly(process.getOutputStream());
                clientGone = copy(process, out);
            } finally {
                deadline.cancel(false);
                finish(process, hls, timedOut.get(), clientGone, stderr);
                running.remove(process);
            }
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                // the completion handler may close the slot while the body still streams: ffmpeg must not outlive it
                Process process = current;
                if (process != null) {
                    process.destroyForcibly();
                }
                permits.release();
                if (publicIp != null) {
                    publicPermits.release();
                    releaseIp(publicIp);
                }
            }
        }
    }

    /** Copies ffmpeg's output to {@code out}; true when the caller went away, ffmpeg then being killed. */
    private static boolean copy(Process process, OutputStream out) {
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = process.getInputStream()) {
            while (true) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException e) {
                    // the stream closed under a killed process, or ffmpeg's output broke: either way it is over
                    log.debug("ffmpeg output ended: {}", e.toString());
                    return false;
                }
                if (read < 0) {
                    return false;
                }
                try {
                    out.write(buffer, 0, read);
                    out.flush();
                } catch (IOException e) {
                    log.debug("Trailer caller went away: {}", e.toString());
                    process.destroyForcibly();
                    return true;
                }
            }
        } catch (IOException e) {
            // closing ffmpeg's output failed: nothing left to read anyway
            return false;
        }
    }

    /** Waits a little for ffmpeg to exit (killing it otherwise) and says how it ended. */
    private void finish(Process process, URI hls, boolean timedOut, boolean clientGone, StderrTail stderr) {
        try {
            if (!process.waitFor(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        String where = hls.getHost() + hls.getPath();
        if (timedOut) {
            log.warn("ffmpeg ran past {} on {}: killed", maxDuration, where);
        } else if (!clientGone && !process.isAlive() && process.exitValue() != 0) {
            log.warn("ffmpeg failed ({}) on {}: {}", process.exitValue(), where, stderr.tail());
        }
        stderr.close();
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // ffmpeg does not read its input: nothing to do
        }
    }

    /**
     * Reads ffmpeg's error output to its end on a virtual thread, so a full pipe can never block ffmpeg, keeping its
     * last {@value #STDERR_TAIL} bytes for the log.
     */
    private static final class StderrTail {

        private final InputStream stream;
        private final ByteArrayOutputStream tail = new ByteArrayOutputStream();
        private final Thread reader;

        StderrTail(InputStream stream) {
            this.stream = stream;
            this.reader = Thread.ofVirtual().name("ffmpeg-stderr").start(this::drain);
        }

        private void drain() {
            byte[] buffer = new byte[1024];
            try (stream) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (tail) {
                        tail.write(buffer, 0, read);
                        if (tail.size() > STDERR_TAIL) {
                            byte[] kept = tail.toByteArray();
                            tail.reset();
                            tail.write(kept, kept.length - STDERR_TAIL, STDERR_TAIL);
                        }
                    }
                }
            } catch (IOException e) {
                // the process is gone: what was read is all there is
            }
        }

        String tail() {
            try {
                reader.join(Duration.ofSeconds(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (tail) {
                return tail.toString(StandardCharsets.UTF_8).strip();
            }
        }

        /** Stops reading when the process somehow left its error output open. */
        void close() {
            if (reader.isAlive()) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // already closed
                }
            }
        }
    }
}
