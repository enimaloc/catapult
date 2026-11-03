package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamCatchUpTest {

    private static final Instant STARTED = Instant.parse("2026-11-03T10:00:00Z");

    private StreamSessionService sessions;
    private StreamStateService streamState;
    private ApplicationEventPublisher publisher;
    private StreamCatchUp catchUp;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        sessions = mock(StreamSessionService.class);
        streamState = mock(StreamStateService.class);
        publisher = mock(ApplicationEventPublisher.class);
        catchUp = new StreamCatchUp(sessions, streamState, publisher);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private StreamSession session(String streamId, Instant endedAt) {
        StreamSession s = new StreamSession();
        s.setUserId(user.getId());
        s.setStreamId(streamId);
        s.setStartedAt(STARTED);
        s.setEndedAt(endedAt);
        return s;
    }

    private ApplicationEvent publishedEvent() {
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void live_noSession_seedsWithoutPublishing() {
        when(sessions.find(user)).thenReturn(Optional.empty());

        catchUp.reconcile(user, Optional.of(new StreamCatchUp.LiveStream("7", STARTED)));

        verify(sessions).recordStart(user, "7", STARTED);
        verify(streamState).restoreLive(user, true);
        verify(streamState, never()).setLive(any(), anyBoolean());
        verifyNoInteractions(publisher);
    }

    @Test
    void live_sameStream_publishesNothing() {
        when(sessions.find(user)).thenReturn(Optional.of(session("7", null)));

        catchUp.reconcile(user, Optional.of(new StreamCatchUp.LiveStream("7", STARTED)));

        verify(streamState).restoreLive(user, true);
        verify(streamState, never()).setLive(any(), anyBoolean());
        verify(sessions, never()).recordStart(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void live_newStream_recordsAndPublishesOnline() {
        when(sessions.find(user)).thenReturn(Optional.of(session("6", null)));

        catchUp.reconcile(user, Optional.of(new StreamCatchUp.LiveStream("7", STARTED)));

        verify(sessions).recordStart(user, "7", STARTED);
        verify(streamState).setLive(user, true);
        assertThat(publishedEvent()).isInstanceOfSatisfying(StreamOnlineEvent.class,
            e -> assertThat(e.getUser()).isSameAs(user));
    }

    @Test
    void live_afterEndedSession_publishesOnline() {
        when(sessions.find(user)).thenReturn(Optional.of(session("7", STARTED.plusSeconds(60))));

        catchUp.reconcile(user, Optional.of(new StreamCatchUp.LiveStream("7", STARTED)));

        verify(sessions).recordStart(user, "7", STARTED);
        verify(streamState).setLive(user, true);
        assertThat(publishedEvent()).isInstanceOf(StreamOnlineEvent.class);
    }

    @Test
    void offline_ongoingSession_recordsEndAndPublishesOffline() {
        when(sessions.find(user)).thenReturn(Optional.of(session("7", null)));

        catchUp.reconcile(user, Optional.empty());

        verify(sessions).recordEnd(any(), any());
        verify(streamState).setLive(user, false);
        assertThat(publishedEvent()).isInstanceOfSatisfying(StreamOfflineEvent.class,
            e -> assertThat(e.getUser()).isSameAs(user));
    }

    @Test
    void offline_noOngoingSession_publishesNothing() {
        when(sessions.find(user)).thenReturn(Optional.of(session("7", STARTED.plusSeconds(60))));

        catchUp.reconcile(user, Optional.empty());

        verify(streamState).restoreLive(user, false);
        verify(streamState, never()).setLive(any(), anyBoolean());
        verify(sessions, never()).recordEnd(any(), any());
        verifyNoInteractions(publisher);
    }
}
