package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.common.dto.AppConfigResponse;
import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.InviteRedemptionDto;
import fr.enimaloc.catapult.common.dto.MyDebugDto;
import fr.enimaloc.catapult.common.dto.UserInfoResponse;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.account.UserRefDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.CclDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.TwDto;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentStatus;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentVariantDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannelsDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.net.ServerSocketFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import tools.jackson.databind.json.JsonMapper;

/**
 * catapult-api as the pages under budget reach it: each route catapult-web calls for them answers the real
 * catapult-common DTO, as JSON, with {@value #SMALL} elements in every collection a page shows, then
 * {@value #LARGE} once {@link #grow() grown}. The channel is {@value #OWNER_LOGIN}'s ({@link #OWNER}); the caller is
 * the subject of the request's bearer, so only {@link #OWNER} is told they own it. A request no route answers gets
 * a 599 and is kept in {@link #unexpected()}. Every request but the background degraded poll is recorded, so a test
 * can {@link #mark()} before a page and read {@link #pageCalls()} after it.
 */
public final class FakeApi extends Dispatcher {

    public static final int SMALL = 2;
    public static final int LARGE = 5;
    public static final String OWNER_LOGIN = "budget-owner";
    public static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000b0001");
    public static final UUID VISITOR = UUID.fromString("00000000-0000-0000-0000-0000000b0002");
    public static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000b0003");
    /** The experiment the {@code /spa/admin/experiments/{id}} row opens. */
    public static final UUID EXPERIMENT = id("experiment", 0);
    /** Polled by DegradedState in the background, whatever page is open: no page's call. */
    static final String DEGRADED = "/api/internal/status/degraded";
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final int UNEXPECTED = 599;

    private final JsonMapper json;
    private final List<Route> routes = new ArrayList<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
    private volatile int size = SMALL;
    private volatile int marked;

    public FakeApi(JsonMapper json) {
        this.json = json;
        route(DEGRADED, caller -> new DegradedStatusDto(false, List.of()));
        route("/api/internal/meta-tags", caller -> list(i -> new MetaTagDto(i + 1, MetaTagAttribute.NAME,
                "budget-tag-" + i, "Budget content " + i, AT)));
        route("/api/internal/users", caller -> new UserRefDto(OWNER, OWNER_LOGIN));
        route("/api/internal/auth/validate", caller -> new UserInfoResponse(caller, twitchId(caller),
                login(caller), avatar(caller), "ACTIVE", List.of("ROLE_USER"), null));
        route("/api/internal/config/app", caller -> new AppConfigResponse("Catapult", 7, List.of("steam")));
        route("/api/internal/users/{id}/dashboard", caller -> channelPage(OWNER.equals(caller)));
        route("/api/internal/users/{id}/seen-features", caller -> List.of());
        route("/api/internal/users/{id}/debug", caller -> new MyDebugDto(false));
        route("/api/internal/users/{id}/settings", caller -> settings());
        route("/api/internal/users/{id}/dtdd-mapping", caller -> new DtddMappingStatusDto(
                new DtddMappingCurrentDto(1L, "Budget DTDD game", 0.9, true), null, false, "1"));
        route("/api/internal/users/{id}/twitchat-events", caller -> Map.of(TwitchatEvents.ALL.getFirst(),
                list(i -> new TwitchatEventActionDto("budget-action-" + i, "RAW", "{}", true))));
        route("/api/internal/users/{id}/chat-commands/enabled", caller -> list(i ->
                new EnabledChatCommandDto("budget" + i, "EVERYONE", null)));
        route("/api/internal/users/{id}/settings/twitchat", caller -> new TwitchatSettingsResponse(true,
                "localhost", 4455, true, id("widget", 0).toString()));
        route("/api/internal/users/{id}/notification-channels", caller -> new NotificationChannelsDto(
                Set.of(NotificationChannel.TWITCHAT, NotificationChannel.STREAM_START_CHAT)));
        route("/api/internal/users/{id}/invite", caller -> new InvitePageData(true, id("invite", 0), "BUDGET23",
                "https://catapult.test/join?invite=BUDGET23", AT,
                list(i -> new InviteRedemptionDto("2" + i, AT.plusSeconds(i)))));
        route("/api/internal/users/{id}/bindings/current", caller -> binding(0));
        route("/api/internal/users/{id}/channels", caller -> new ChannelListResponse(twitchId(caller),
                list(i -> new ChannelDto(id("channel", i), "3" + i, "budget-channel-" + i, avatar(OWNER), i % 2 == 0))));
        route("/api/internal/users/{id}/api-token", caller -> new ApiTokenStatusDto(true, AT));
        route("/api/internal/admin/members", caller -> members());
        route("/api/internal/admin/experiments", caller -> list(i -> new ExperimentSummaryDto(
                id("experiment", i), "budget.experiment-" + i, "Budget experiment " + i, ExperimentStatus.ACTIVE,
                variants(), i)));
        route("/api/internal/admin/experiments/{id}/settings", caller -> experiment());
        route("/api/internal/admin/experiments/{id}/assignments", caller -> assignments());
        route("/api/internal/admin/groups", caller -> list(i -> new GroupSummaryDto(id("group", i),
                "budget-group-" + i, "Budget group " + i, "A budget group", i)));
        route("/api/internal/admin/flags", caller -> list(i -> new FlagKeyDto("budget-flag-" + i,
                List.of("on", "off"))));
    }

    /**
     * A MockWebServer to set a FakeApi on, its sockets without Nagle's delay: it writes an answer's headers and body
     * apart, which the delayed ACK of the other end would otherwise hold some 40 ms, every call.
     */
    public static MockWebServer server() {
        MockWebServer server = new MockWebServer();
        server.setServerSocketFactory(new ServerSocketFactory() {
            @Override
            public ServerSocket createServerSocket() throws IOException {
                return new ServerSocket() {
                    @Override
                    public Socket accept() throws IOException {
                        Socket socket = super.accept();
                        socket.setTcpNoDelay(true);
                        return socket;
                    }
                };
            }

            @Override
            public ServerSocket createServerSocket(int port) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ServerSocket createServerSocket(int port, int backlog) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ServerSocket createServerSocket(int port, int backlog, InetAddress address) {
                throw new UnsupportedOperationException();
            }
        });
        return server;
    }

    /** From now on every collection has {@value #LARGE} elements. */
    public void grow() {
        size = LARGE;
    }

    /** Starts a page: {@link #pageCalls()} lists the calls from here on. */
    public void mark() {
        marked = calls.size();
    }

    /** {@code METHOD path?query} of each call since {@link #mark()}, the degraded poll left out, in order. */
    public List<String> pageCalls() {
        synchronized (calls) {
            return List.copyOf(calls.subList(marked, calls.size()));
        }
    }

    /** {@code METHOD path?query} of every call no route answered (each got a 599). */
    public List<String> unexpected() {
        synchronized (unexpected) {
            return List.copyOf(unexpected);
        }
    }

    /** A session JWT as catapult-api issues one for {@code subject}; catapult-web reads it without checking it. */
    public static String jwt(UUID subject, List<String> roles) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"" + subject + "\",\"username\":\"" + login(subject) + "\",\"roles\":["
                + String.join(",", roles.stream().map(r -> "\"" + r + "\"").toList()) + "],\"exp\":"
                + Instant.now().plusSeconds(3600).getEpochSecond() + "}";
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        String target = request.getPath() == null ? "" : request.getPath();
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String call = request.getMethod() + " " + target;
        if (!path.equals(DEGRADED)) {
            calls.add(call);
        }
        for (Route route : routes) {
            if ("GET".equals(request.getMethod()) && route.pattern().matcher(path).matches()) {
                Object body = route.answer().apply(caller(request.getHeader("Authorization")));
                // no connection kept open past its answer, so that the server can be shut down
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                        .setBody(json.writeValueAsString(body));
            }
        }
        unexpected.add(call);
        return new MockResponse().setResponseCode(UNEXPECTED)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
    }

    private void route(String template, Function<UUID, Object> answer) {
        routes.add(new Route(Pattern.compile(template.replace("{id}", "[^/]+")), answer));
    }

    private ChannelPageData channelPage(boolean owner) {
        List<BindingDto> bindings = list(this::binding);
        return new ChannelPageData(
                new ChannelUserDto(OWNER.toString(), twitchId(OWNER), OWNER_LOGIN, avatar(OWNER)),
                OWNER_LOGIN, owner, true, true, new GameDto(bindings.getFirst().id(), "Budget game 0", "STEAM"),
                new PagedBindings(0, 1, size, bindings),
                ccls(), Set.of("budget-ccl-0"), tws(), Set.of("budget-tw-0"),
                null, null,
                new SteamData(true, true, false, false, false, false, 60),
                new XboxData(true),
                new MinecraftData("ACCEPTED", "BudgetCrafter", "catapult-bot"),
                new ObsData(true, "localhost", 4455, true),
                TwitchatData.of("stable"),
                id("example", 0).toString(),
                new CustomBindingsStateDto(true, list(i -> new CustomBindingDto(id("custom", i), "Budget binding " + i,
                        "50" + i, "Budget category " + i, List.of("budget-ccl-0"), List.of("budget-tw-0"), i == 0)),
                        id("custom", 0), AT));
    }

    private BindingDto binding(int i) {
        return new BindingDto(id("binding", i).toString(), "VALIDATED", "STEAM", "Budget game " + i, "40" + i,
                "Budget category " + i, false, true, Set.of("budget-ccl-0"), true, false, Set.of("budget-tw-0"));
    }

    private UserSettingsDto settings() {
        return new UserSettingsDto(true, Set.of("budget-ccl-0"), "509", "Just Chatting", Set.of("budget-ccl-1"),
                true, true, false, "510", "Games + Demos", Set.of(), ccls(), true, Set.of("budget-tw-0"), tws(), 30,
                true);
    }

    private List<CclDto> ccls() {
        return list(i -> new CclDto("budget-ccl-" + i, "Budget label " + i));
    }

    private List<TwDto> tws() {
        return list(i -> new TwDto("budget-tw-" + i, "Budget warning " + i));
    }

    private AdminMembersPage members() {
        List<AdminMemberDto> members = list(i -> new AdminMemberDto(id("member", i), "6" + i, "budget-member-" + i,
                avatar(OWNER), null, false, true, "ACTIVE", AT, null));
        Map<UUID, Boolean> live = new LinkedHashMap<>();
        members.forEach(member -> live.put(member.id(), true));
        return new AdminMembersPage(members, live, false, 0, 1, members.size());
    }

    private List<ExperimentVariantDto> variants() {
        return list(i -> new ExperimentVariantDto(id("variant", i), i == 0 ? "control" : "variant-" + i,
                "Variant " + i, 100 / size, i == 0));
    }

    private ExperimentSettingsDto experiment() {
        List<ExperimentVariantDto> variants = variants();
        return new ExperimentSettingsDto(EXPERIMENT, "budget.experiment-0", "Budget experiment 0", "A budget experiment",
                ExperimentStatus.ACTIVE, variants,
                list(i -> new ExperimentRuleDto(id("rule", i), "PERCENTAGE", 10 * (i + 1), null, null, null)),
                list(i -> new ExperimentOverrideDto(id("override", i), "USER", "FORCE", i, "budget-member-" + i,
                        null, null, null, variants.getFirst().id())));
    }

    private ExperimentAssignmentsDto assignments() {
        List<ExperimentAssignmentDto> content = list(i -> new ExperimentAssignmentDto(id("member", i),
                "budget-member-" + i, id("variant", i), AT, i == 0));
        Map<UUID, Long> counts = new LinkedHashMap<>();
        variants().forEach(variant -> counts.put(variant.id(), 1L));
        return new ExperimentAssignmentsDto(new AdminPage<>(content, 0, 1, content.size()), counts);
    }

    private <T> List<T> list(IntFunction<T> element) {
        return IntStream.range(0, size).mapToObj(element).toList();
    }

    /** The subject of {@code authorization}'s bearer JWT; null without one. */
    private UUID caller(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = authorization.substring("Bearer ".length()).split("\\.");
        if (parts.length < 2) {
            return null;
        }
        Map<?, ?> claims = json.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        return UUID.fromString(String.valueOf(claims.get("sub")));
    }

    private static UUID id(String kind, int i) {
        return UUID.nameUUIDFromBytes((kind + "-" + i).getBytes(StandardCharsets.UTF_8));
    }

    private static String login(UUID subject) {
        if (OWNER.equals(subject)) {
            return OWNER_LOGIN;
        }
        return VISITOR.equals(subject) ? "budget-visitor" : "budget-admin";
    }

    private static String twitchId(UUID subject) {
        return String.valueOf(1000 + List.of(OWNER, VISITOR, ADMIN).indexOf(subject));
    }

    private static String avatar(UUID subject) {
        return "https://static-cdn.test/" + subject + ".png";
    }

    private record Route(Pattern pattern, Function<UUID, Object> answer) {}
}
