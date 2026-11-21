package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.trailer.FfmpegTrailerStreamer;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerBusyException;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerSourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The MP4 trailer routes (version 3), with the real API security. */
@WebMvcTest(
        controllers = TrailerController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, TrailerControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class TrailerControllerTest {

    private static final String TOKEN = "cat_" + "t".repeat(43);
    private static final String READ_TOKEN = "catr_" + "r".repeat(43);
    private static final String ME = "/api/me/game/trailer.mp4";
    private static final String STEAM = "/api/games/steam/620/trailer.mp4";
    private static final String XBOX = "/api/games/xbox/9NBLGGH4R315/trailer.mp4";
    private static final URI HLS = URI.create("https://video.akamai.steamstatic.com/hls_264_master.m3u8");

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean TrailerSourceResolver resolver;
    @MockitoBean FfmpegTrailerStreamer streamer;
    @MockitoBean IpFetchBudgets budgets;

    private UserAccount user;
    private final AtomicInteger ipCalls = new AtomicInteger();
    private final AtomicBoolean ipBudgetLeft = new AtomicBoolean(true);
    private final FetchBudget ipBudget = new FetchBudget() {
        @Override
        public boolean tryAcquire() {
            ipCalls.incrementAndGet();
            return ipBudgetLeft.get();
        }

        @Override
        public Duration retryAfter() {
            return Duration.ofSeconds(12);
        }
    };
    private final FfmpegTrailerStreamer.Reservation reservation = mock(FfmpegTrailerStreamer.Reservation.class);

    @BeforeEach
    void setUp() throws Exception {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.FULL)));
        when(tokens.authenticate(READ_TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
        when(budgets.forIp(any())).thenReturn(ipBudget);
        when(resolver.current(any())).thenReturn(Optional.empty());
        when(resolver.steam(any(), any())).thenReturn(Optional.empty());
        when(resolver.xbox(any(), any())).thenReturn(Optional.empty());
        when(streamer.reserve()).thenReturn(reservation);
        when(streamer.reservePublic(any())).thenReturn(reservation);
        when(streamer.maxDuration()).thenReturn(Duration.ofMinutes(10));
        doAnswer(invocation -> {
            invocation.<OutputStream>getArgument(1).write("mp4-bytes".getBytes());
            return null;
        }).when(reservation).stream(eq(HLS), any());
    }

    private static MockHttpServletRequestBuilder v3(MockHttpServletRequestBuilder request) {
        return request.header(ApiV2.VERSION_HEADER, ApiV2.VERSION_3).with(r -> {
            r.setRemoteAddr("203.0.113.7");
            return r;
        });
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return v3(request).header("Authorization", "Bearer " + TOKEN);
    }

    @Test
    void currentGame_streamsTheMp4() throws Exception {
        when(resolver.current(user)).thenReturn(Optional.of(HLS));

        MvcResult started = mvc.perform(withToken(get(ME))).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                .andExpect(content().bytes("mp4-bytes".getBytes()));
        // by the body, then by the completion handler (idempotent in the real reservation)
        verify(reservation, atLeastOnce()).close();
        verify(streamer).reserve();
        verify(streamer, never()).reservePublic(any());
    }

    @Test
    void head_takesTheReadOnlyTokenLikeGet() throws Exception {
        when(resolver.current(user)).thenReturn(Optional.of(HLS));

        mvc.perform(v3(head(ME)).queryParam("token", READ_TOKEN)).andExpect(status().isOk());
        verifyNoInteractions(streamer);
    }

    @Test
    void currentGame_needsAPersonalToken() throws Exception {
        mvc.perform(v3(get(ME))).andExpect(status().isUnauthorized());
        verifyNoInteractions(streamer);
    }

    @Test
    void head_noBodyNoFfmpeg() throws Exception {
        when(resolver.current(user)).thenReturn(Optional.of(HLS));
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{withToken(head(ME)),
                v3(head(STEAM))}) {
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(request().asyncNotStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(content().bytes(new byte[0]));
        }
        verifyNoInteractions(streamer);
    }

    @Test
    void rangeHeader_ignored_200() throws Exception {
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));

        MvcResult started = mvc.perform(v3(get(STEAM)).header(HttpHeaders.RANGE, "bytes=100-"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "none"))
                .andExpect(content().bytes("mp4-bytes".getBytes()));
    }

    @Test
    void publicRoutes_countAgainstTheCallersIpBudget() throws Exception {
        // a cached URL: the resolver takes nothing from the budget, the stream itself still does
        when(resolver.xbox(eq("9NBLGGH4R315"), eq(ipBudget))).thenReturn(Optional.of(HLS));

        MvcResult started = mvc.perform(v3(get(XBOX)).header(HttpHeaders.ORIGIN, "https://overlay.example"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        verify(budgets).forIp("203.0.113.7");
        assertThat(ipCalls).hasValue(1);
        verify(streamer).reservePublic("203.0.113.7");
        verify(streamer, never()).reserve();
    }

    @Test
    void cachedUrl_spentIpBudget_429_noFfmpeg() throws Exception {
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));
        ipBudgetLeft.set(false);

        mvc.perform(v3(get(STEAM)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "12"));
        verifyNoInteractions(streamer);
    }

    @Test
    void head_costsNoStream() throws Exception {
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));

        mvc.perform(v3(head(STEAM))).andExpect(status().isOk());
        assertThat(ipCalls).hasValue(0);
    }

    @Test
    void spentIpBudget_429() throws Exception {
        when(resolver.steam(eq("620"), any())).thenThrow(new UpstreamBudgetExceededException(Duration.ofSeconds(12)));

        mvc.perform(v3(get(STEAM)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "12"));
    }

    @Test
    void noTrailer_404() throws Exception {
        mvc.perform(withToken(get(ME))).andExpect(status().isNotFound());
        mvc.perform(withToken(head(ME))).andExpect(status().isNotFound());
        mvc.perform(v3(get(STEAM))).andExpect(status().isNotFound());
        mvc.perform(v3(head(XBOX))).andExpect(status().isNotFound());
        verify(streamer, never()).reserve();
        verify(streamer, never()).reservePublic(any());
    }

    @Test
    void allFfmpegBusy_503WithRetryAfter() throws Exception {
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));
        when(streamer.reservePublic(any())).thenThrow(new TrailerBusyException(Duration.ofSeconds(30)));

        mvc.perform(v3(get(STEAM)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));
    }

    /** A malformed id is refused before any store lookup, upstream call or database write. */
    @Test
    void malformedIds_400_withoutAnyLookup() throws Exception {
        for (String path : List.of("/api/games/steam/abc/trailer.mp4", "/api/games/steam/12345678901/trailer.mp4",
                "/api/games/xbox/9NBLGGH4R31/trailer.mp4", "/api/games/xbox/9NBLGGH4R315X/trailer.mp4",
                "/api/games/xbox/9NBLGGH4R3-5/trailer.mp4")) {
            mvc.perform(v3(get(path))).andExpect(status().isBadRequest());
            mvc.perform(v3(head(path))).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(resolver, streamer);
    }

    @Test
    void trailer_v2_notFound() throws Exception {
        when(resolver.steam(eq("620"), any())).thenReturn(Optional.of(HLS));

        mvc.perform(get(STEAM).header(ApiV2.VERSION_HEADER, ApiV2.VERSION)).andExpect(status().isNotFound());
        mvc.perform(get(ME).header(ApiV2.VERSION_HEADER, ApiV2.VERSION).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
        verifyNoInteractions(streamer);
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
