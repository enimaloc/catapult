package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadMode;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatEventActionRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.NO_GAME_DETECTED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwitchatEventActionServiceTest {

    @Mock TwitchatEventActionRepository repository;
    @Mock TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @Mock JdbcTemplate jdbc;

    TwitchatEventActionService service;
    UserAccount user;
    TwitchatWidgetSettings settings;

    @BeforeEach
    void setUp() {
        TwitchatCatalog catalog = TwitchatCatalog.load();
        service = new TwitchatEventActionService(repository, widgetSettingsRepository, jdbc,
                new TwitchatPayloadValidator(catalog));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        settings = new TwitchatWidgetSettings();
        settings.setUser(user);
        settings.setEnabled(true);
    }

    @Test
    void replace_deletesTheEventFirst_thenSavesInOrder() {
        service.replace(user, STREAM_STARTED, List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"a\"}", true),
                new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"x\":1}", false)));

        var inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).deleteAllOfEvent(user, STREAM_STARTED);
        ArgumentCaptor<TwitchatEventAction> saved = ArgumentCaptor.forClass(TwitchatEventAction.class);
        inOrder.verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TwitchatEventAction::getPosition, TwitchatEventAction::getMode, TwitchatEventAction::isEnabled)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, TwitchatPayloadMode.FORM, true),
                        org.assertj.core.groups.Tuple.tuple(1, TwitchatPayloadMode.RAW, false));
    }

    @Test
    void replace_withAnInvalidPayload_touchesNothing() {
        assertThatThrownBy(() -> service.replace(user, STREAM_STARTED,
                List.of(new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{}", true))))
                .isInstanceOf(TwitchatValidationException.class)
                .satisfies(e -> assertThat(((TwitchatValidationException) e).errors()).hasSize(1));
        verify(repository, never()).deleteAllOfEvent(any(), any());
    }

    @Test
    void all_listsEveryEvent_evenEmpty() {
        TwitchatEventAction action = new TwitchatEventAction();
        action.setEventType(STREAM_STARTED);
        when(repository.findByUserOrderByPositionAsc(user)).thenReturn(List.of(action));

        var all = service.all(user);
        assertThat(all).containsKeys(STREAM_STARTED, NO_GAME_DETECTED);
        assertThat(all.get(STREAM_STARTED)).containsExactly(action);
        assertThat(all.get(NO_GAME_DETECTED)).isEmpty();
    }

    @Test
    void ensureInitialized_convertsActivePresets_andSeedsDefaultsForTheOthers() {
        when(widgetSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(repository.existsByUser(user)).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getString("event_type")).thenReturn("STREAM_STARTED");
            when(rs.getString("payload_json")).thenReturn("{\"message\":\"Custom live\"}");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(user.getId()));

        service.ensureInitialized(user);

        ArgumentCaptor<TwitchatEventAction> saved = ArgumentCaptor.forClass(TwitchatEventAction.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(5)
                .allSatisfy(a -> assertThat(a.getAction()).isEqualTo("CUSTOM_CHAT_MESSAGE"));
        assertThat(saved.getAllValues()).filteredOn(a -> a.getEventType() == STREAM_STARTED)
                .singleElement().satisfies(a -> assertThat(a.getDataJson()).contains("Custom live"));
        assertThat(settings.isEventActionsInitialized()).isTrue();
        verify(widgetSettingsRepository).save(settings);
    }

    @Test
    void ensureInitialized_withoutLegacyTables_seedsDefaults_withoutQueryingThem() {
        when(widgetSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(repository.existsByUser(user)).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        service.ensureInitialized(user);

        verify(repository, org.mockito.Mockito.times(5)).save(any());
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class), eq(user.getId()));
    }

    @Test
    void ensureInitialized_skipsAnUnknownLegacyEvent_andStillSeedsTheOthers() {
        when(widgetSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(repository.existsByUser(user)).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getString("event_type")).thenReturn("REMOVED_EVENT");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(user.getId()));

        service.ensureInitialized(user);

        verify(repository, org.mockito.Mockito.times(5)).save(any());
        assertThat(settings.isEventActionsInitialized()).isTrue();
    }

    @Test
    void ensureInitialized_isANoOpOnceDone_orWhenRowsExist() {
        settings.setEventActionsInitialized(true);
        when(widgetSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        service.ensureInitialized(user);
        verify(repository, never()).save(any());

        settings.setEventActionsInitialized(false);
        when(repository.existsByUser(user)).thenReturn(true);
        service.ensureInitialized(user);
        verify(repository, never()).save(any());
        assertThat(settings.isEventActionsInitialized()).isTrue();
    }

    @Test
    void deleteAllOfUser_deletesTheActions_thenTheLegacyPresets_activeOnesFirst() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        service.deleteAllOfUser(user);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, jdbc);
        order.verify(repository).deleteAllOfUser(user);
        order.verify(jdbc).update("DELETE FROM twitchat_active_preset WHERE user_id = ?", user.getId());
        order.verify(jdbc).update("DELETE FROM twitchat_payload_preset WHERE user_id = ?", user.getId());
    }

    @Test
    void deleteAllOfUser_withoutLegacyTables_deletesOnlyTheActions() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenThrow(new DataAccessResourceFailureException("no to_regclass"));

        service.deleteAllOfUser(user);

        verify(repository).deleteAllOfUser(user);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
