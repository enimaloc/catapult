package fr.enimaloc.catapult.service.enrichment.trailer;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ffmpeg remuxing a trailer straight into the response, against a fake process. */
class FfmpegTrailerStreamerTest {

    private static final URI HLS = URI.create(
            "https://video.akamai.steamstatic.com/store_trailers/620/2/abc/1750650443/hls_264_master.m3u8?t=1");

    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<FakeProcess> processes = new ArrayList<>();
    private FfmpegTrailerStreamer streamer;
    /** What the next launched process writes on its stdout; null for a process that never ends by itself. */
    private byte[] nextOutput = "fragmented-mp4".getBytes();

    @BeforeEach
    void setUp() {
        properties.setFfmpegMaxProcesses(4);
        properties.setFfmpegMaxDuration(Duration.ofMinutes(10));
        streamer = newStreamer();
    }

    @AfterEach
    void tearDown() {
        streamer.shutdown();
    }

    private FfmpegTrailerStreamer newStreamer() {
        return new FfmpegTrailerStreamer(properties, command -> {
            commands.add(command);
            FakeProcess process = new FakeProcess(nextOutput);
            processes.add(process);
            return process;
        });
    }

    @Test
    void command_isCopyRemuxToFragmentedMp4() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        streamer.stream(HLS, out);

        assertThat(out.toString()).isEqualTo("fragmented-mp4");
        List<String> command = commands.getFirst();
        assertThat(command.getFirst()).isEqualTo("ffmpeg");
        assertThat(command).containsSubsequence("-hide_banner", "-loglevel", "error");
        assertThat(command).containsSubsequence("-protocol_whitelist", "https,tls,tcp,crypto", "-i", HLS.toString());
        assertThat(command).containsSubsequence("-i", HLS.toString(), "-c", "copy", "-movflags",
                "frag_keyframe+empty_moov", "-f", "mp4", "pipe:1");
        assertThat(command.getLast()).isEqualTo("pipe:1");
        assertThat(processes.getFirst().stdinClosed).isTrue();
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void foreignUrl_neverReachesFfmpeg() {
        assertThatThrownBy(() -> streamer.stream(URI.create("file:///etc/passwd"), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void fifthConcurrentStream_throwsBusy() throws IOException {
        List<FfmpegTrailerStreamer.Reservation> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(streamer.reserve());
        }

        assertThatThrownBy(() -> streamer.stream(HLS, new ByteArrayOutputStream()))
                .isInstanceOf(TrailerBusyException.class)
                .satisfies(e -> assertThat(((TrailerBusyException) e).retryAfter()).isPositive());
        assertThat(commands).isEmpty();

        held.getFirst().close();
        // closing twice gives back one permit only
        held.getFirst().close();
        streamer.stream(HLS, new ByteArrayOutputStream());
        assertThat(streamer.availablePermits()).isEqualTo(1);
    }

    @Test
    void publicCallers_leaveTheLastSlotToTheAuthenticatedRoute() throws IOException {
        List<FfmpegTrailerStreamer.Reservation> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(streamer.reservePublic("198.51.100." + i));
        }

        assertThatThrownBy(() -> streamer.reservePublic("198.51.100.9")).isInstanceOf(TrailerBusyException.class);
        // the refused public caller left nothing behind
        assertThat(streamer.trackedIps()).isEqualTo(3);
        FfmpegTrailerStreamer.Reservation obs = streamer.reserve();
        assertThat(streamer.availablePermits()).isZero();

        obs.close();
        held.forEach(FfmpegTrailerStreamer.Reservation::close);
        assertThat(streamer.availablePermits()).isEqualTo(4);
        assertThat(streamer.trackedIps()).isZero();
    }

    @Test
    void onePublicStreamPerIp() {
        FfmpegTrailerStreamer.Reservation first = streamer.reservePublic("203.0.113.7");

        assertThatThrownBy(() -> streamer.reservePublic("203.0.113.7")).isInstanceOf(TrailerBusyException.class);
        FfmpegTrailerStreamer.Reservation other = streamer.reservePublic("203.0.113.8");

        first.close();
        first.close();
        FfmpegTrailerStreamer.Reservation again = streamer.reservePublic("203.0.113.7");
        again.close();
        other.close();
        assertThat(streamer.trackedIps()).isZero();
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void onePublicStreamPerIpv6Slash64() {
        FfmpegTrailerStreamer.Reservation first = streamer.reservePublic("2001:db8:0:1::a");

        assertThatThrownBy(() -> streamer.reservePublic("2001:db8:0:1:ffff::b"))
                .isInstanceOf(TrailerBusyException.class);
        assertThatThrownBy(() -> streamer.reservePublic("[2001:db8:0:1::c]"))
                .isInstanceOf(TrailerBusyException.class);
        FfmpegTrailerStreamer.Reservation other = streamer.reservePublic("2001:db8:0:2::a");

        first.close();
        other.close();
        FfmpegTrailerStreamer.Reservation ipv4 = streamer.reservePublic("203.0.113.7");
        assertThatThrownBy(() -> streamer.reservePublic("::ffff:203.0.113.7"))
                .isInstanceOf(TrailerBusyException.class);
        ipv4.close();
        assertThat(streamer.trackedIps()).isZero();
    }

    @Test
    void closeDuringStream_killsFfmpegBeforeFreeingTheSlot() throws Exception {
        nextOutput = null;
        FfmpegTrailerStreamer.Reservation reservation = streamer.reservePublic("203.0.113.7");
        CountDownLatch writing = new CountDownLatch(1);
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                writing.countDown();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writing.countDown();
            }
        };
        Thread body = Thread.ofVirtual().start(() -> {
            try {
                reservation.stream(HLS, out);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();

        // the async completion handler, while the body is blocked on ffmpeg's stalled output
        reservation.close();

        assertThat(processes.getFirst().destroyedForcibly).isTrue();
        body.join(Duration.ofSeconds(5));
        assertThat(body.isAlive()).isFalse();
        assertThat(streamer.availablePermits()).isEqualTo(4);
        assertThat(streamer.trackedIps()).isZero();
    }

    @Test
    void singleSlot_neverServedToPublicCallers() {
        properties.setFfmpegMaxProcesses(1);
        streamer.shutdown();
        streamer = newStreamer();

        assertThatThrownBy(() -> streamer.reservePublic("203.0.113.7")).isInstanceOf(TrailerBusyException.class);
        assertThat(streamer.trackedIps()).isZero();
        streamer.reserve().close();
    }

    @Test
    void shutDown_neverLeavesAStartedFfmpegRunning() {
        streamer.shutdown();

        assertThatThrownBy(() -> streamer.stream(HLS, new ByteArrayOutputStream())).isInstanceOf(IOException.class);
        assertThat(processes.getFirst().destroyedForcibly).isTrue();
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void clientDisconnect_killsProcessAndReleasesPermit() throws Exception {
        nextOutput = null;
        OutputStream gone = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        streamer.stream(HLS, gone);

        assertThat(processes.getFirst().destroyedForcibly).isTrue();
        assertThat(processes.getFirst().stderrRead.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void exceedsMaxDuration_killed() throws IOException {
        properties.setFfmpegMaxDuration(Duration.ofMillis(100));
        streamer.shutdown();
        streamer = newStreamer();
        nextOutput = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long start = System.nanoTime();
        streamer.stream(HLS, out);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(processes.getFirst().destroyedForcibly).isTrue();
        assertThat(out.toString()).isEqualTo("partial");
        assertThat(streamer.availablePermits()).isEqualTo(4);
    }

    @Test
    void failingLaunch_releasesPermit() {
        FfmpegTrailerStreamer broken = new FfmpegTrailerStreamer(properties, command -> {
            throw new IOException("ffmpeg: not found");
        });
        try {
            assertThatThrownBy(() -> broken.stream(HLS, new ByteArrayOutputStream())).isInstanceOf(IOException.class);
            assertThat(broken.availablePermits()).isEqualTo(4);
        } finally {
            broken.shutdown();
        }
    }

    @Test
    void wiresInASpringContext() {
        new ApplicationContextRunner()
                .withBean(EnrichmentProperties.class)
                .withUserConfiguration(FfmpegTrailerStreamer.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(FfmpegTrailerStreamer.class));
    }

    /**
     * A process writing {@code output} then exiting 0; with no output, one that writes "partial" then blocks until
     * killed, its stdout then ending.
     */
    private static final class FakeProcess extends Process {

        private final CountDownLatch killed = new CountDownLatch(1);
        private final CountDownLatch stderrRead = new CountDownLatch(1);
        private final InputStream stdout;
        volatile boolean destroyedForcibly;
        volatile boolean stdinClosed;

        FakeProcess(byte[] output) {
            this.stdout = output != null ? new ByteArrayInputStream(output) : new InputStream() {
                private final byte[] partial = "partial".getBytes();
                private int sent;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0];
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (sent < partial.length) {
                        int n = Math.min(len, partial.length - sent);
                        System.arraycopy(partial, sent, b, off, n);
                        sent += n;
                        return n;
                    }
                    try {
                        killed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                }

                @Override
                public void close() {
                    stdinClosed = true;
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new InputStream() {
                @Override
                public int read() {
                    stderrRead.countDown();
                    return -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    stderrRead.countDown();
                    return -1;
                }
            };
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (stdout instanceof ByteArrayInputStream) {
                return 0;
            }
            killed.await();
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return stdout instanceof ByteArrayInputStream || killed.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException();
            }
            return destroyedForcibly ? 137 : 0;
        }

        @Override
        public boolean isAlive() {
            return !(stdout instanceof ByteArrayInputStream) && killed.getCount() > 0;
        }

        @Override
        public void destroy() {
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly = true;
            killed.countDown();
            return this;
        }
    }
}
