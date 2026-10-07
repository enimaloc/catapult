package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import javax.net.SocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IrcTwitchChatService against a local IRC server: handshake, chat lines, PING, reconnection. */
class IrcTwitchChatServiceFlowsTest {

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Socket> accepted = new ArrayList<>();
    private ServerSocket server;
    private IrcTwitchChatService service;
    private UserAccount user;

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        server.setSoTimeout(5000);
        service = new IrcTwitchChatService(tokens, users, encryption, publisher, mock(TwitchHelixChannelClient.class),
                meters, new TwitchChatRateLimiter(18, 3000, 30000, meters));
        service.socketFactory = new LocalSocketFactory(server.getLocalPort());

        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("b-1");
        user.setTwitchUsername("Streamer");
        OAuthToken token = new OAuthToken();
        token.setAccessToken("enc");
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(encryption.decrypt("enc")).thenReturn("raw");
    }

    @AfterEach
    void tearDown() throws IOException {
        service.disconnect(user);
        service.shutdown();
        for (Socket socket : accepted) socket.close();
        server.close();
    }

    /** The next client connection, with a reader on what the service writes. */
    private Peer accept() throws IOException {
        Socket socket = server.accept();
        accepted.add(socket);
        socket.setSoTimeout(5000);
        return new Peer(socket);
    }

    private record Peer(Socket socket, BufferedReader in, PrintWriter out) {
        Peer(Socket socket) throws IOException {
            this(socket,
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8));
        }

        List<String> handshake() throws IOException {
            return List.of(in.readLine(), in.readLine(), in.readLine(), in.readLine());
        }
    }

    /** Connects every socket to the local test server, whatever host/port is asked for. */
    private static final class LocalSocketFactory extends SocketFactory {
        private final int port;

        LocalSocketFactory(int port) {
            this.port = port;
        }

        @Override
        public Socket createSocket(String host, int ignoredPort) throws IOException {
            return new Socket(InetAddress.getLoopbackAddress(), port);
        }

        @Override
        public Socket createSocket(String host, int p, InetAddress localHost, int localPort) throws IOException {
            return createSocket(host, p);
        }

        @Override
        public Socket createSocket(InetAddress host, int p) throws IOException {
            return createSocket(host.getHostName(), p);
        }

        @Override
        public Socket createSocket(InetAddress address, int p, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(address.getHostName(), p);
        }
    }

    @Nested
    class Connection {
        @Test
        void init_logsInAndJoinsTheChannel() throws IOException {
            when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(user));

            service.init();

            assertThat(accept().handshake()).containsExactly(
                    "CAP REQ :twitch.tv/tags twitch.tv/commands", "PASS oauth:raw", "NICK streamer", "JOIN #streamer");
            assertThat(meters.find("catapult.chat.messages").tag("transport", "irc").counter()).isNotNull();
        }

        @Test
        void chatLinesBecomeCommands_andPingsArePonged() throws IOException {
            service.onAccountCreated(new AccountCreatedEvent(this, user));
            Peer peer = accept();
            peer.handshake();

            peer.out().println("PING :tmi.twitch.tv");
            assertThat(peer.in().readLine()).isEqualTo("PONG :tmi.twitch.tv");

            peer.out().println("@badges=vip/1;user-id=42 :fan!fan@fan.tmi.twitch.tv PRIVMSG #streamer :!lurk now");
            ArgumentCaptor<ChatCommandEvent> event = ArgumentCaptor.forClass(ChatCommandEvent.class);
            verify(publisher, timeout(3000)).publishEvent(event.capture());
            assertThat(event.getValue().getCommand()).isEqualTo("!lurk");
            assertThat(event.getValue().getArgs()).containsExactly("now");
            assertThat(event.getValue().getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.VIP);
            assertThat(event.getValue().getSenderTwitchId()).isEqualTo("42");
            assertThat(service.connectionCount()).isEqualTo(1);

            service.sendMessage(user, "hi");
            assertThat(peer.in().readLine()).isEqualTo("PRIVMSG #streamer :hi");
        }

        @Test
        void disconnect_closesTheSocket() throws IOException {
            service.connect(user);
            Peer peer = accept();
            peer.handshake();

            service.disconnect(user);

            assertThat(peer.in().readLine()).isNull();
            assertThat(service.connectionCount()).isZero();
        }

        @Test
        void aResetConnection_isReopened() throws IOException {
            service.connect(user);
            Peer first = accept();
            first.handshake();

            first.socket().setSoLinger(true, 0);
            first.socket().close();

            assertThat(accept().handshake()).contains("JOIN #streamer");
        }

        @Test
        void withoutATwitchToken_nothingConnects() throws IOException {
            when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());

            service.connect(user);

            server.setSoTimeout(300);
            org.assertj.core.api.Assertions.assertThatThrownBy(server::accept)
                    .isInstanceOf(java.net.SocketTimeoutException.class);
        }

        @Test
        void sendingWhileDisconnected_isDropped() {
            service.sendMessage(user, "hi");

            assertThat(service.connectionCount()).isZero();
        }

        @Test
        void shutdown_closesEverySocket() throws Exception {
            service.connect(user);
            Peer peer = accept();
            peer.handshake();

            service.shutdown();

            assertThat(peer.in().readLine()).isNull();
            assertThat(((java.util.concurrent.ExecutorService) org.springframework.test.util.ReflectionTestUtils
                    .getField(service, "executor")).awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    class Lines {
        private final PrintWriter writer = new PrintWriter(new StringWriter(), true);

        @Test
        void malformedOrNonChatLines_areIgnored() {
            for (String line : List.of(
                    "@badges=broadcaster/1",
                    ":tmi.twitch.tv",
                    ":tmi.twitch.tv 001 streamer :Welcome",
                    "PRIVMSG #streamer",
                    "@user-id=;badges= :x!x@x PRIVMSG #streamer :!cmd")) {
                service.handleLine(user, writer, line);
            }

            ArgumentCaptor<ChatCommandEvent> event = ArgumentCaptor.forClass(ChatCommandEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue().getArgs()).isEmpty();
            assertThat(event.getValue().getSenderTwitchId()).isNull();
            assertThat(event.getValue().getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.VIEWERS);
        }

        @Test
        void untaggedChatLines_areCountedEvenWithoutACommand() {
            service.handleLine(user, writer, ":x!x@x PRIVMSG #streamer :hello");

            verify(publisher, never()).publishEvent(any());
            assertThat(meters.find("catapult.chat.messages").tag("transport", "irc").counter().count()).isEqualTo(1);
        }
    }
}
