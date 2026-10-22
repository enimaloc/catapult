package fr.enimaloc.catapult.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentVariantRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminNotificationsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
class ApiAdminNotificationsControllerTest {
    @MockitoBean ChannelAccessService channelAccessService;

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean NotificationService service;
    @MockitoBean NotificationRepository repo;
    @MockitoBean UserAccountRepository userRepo;
    @MockitoBean fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository commandRepository;
    @MockitoBean ExperimentRepository experimentRepository;
    @MockitoBean ExperimentVariantRepository variantRepository;
    @MockitoBean ExperimentAssignmentRepository assignmentRepository;

    private Experiment experiment;
    private ExperimentVariant variant;

    private void experimentFixture() {
        UserAccount admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(admin));
        experiment = new Experiment();
        experiment.setId(UUID.randomUUID());
        variant = new ExperimentVariant();
        variant.setId(UUID.randomUUID());
        variant.setExperiment(experiment);
        when(experimentRepository.findById(experiment.getId())).thenReturn(Optional.of(experiment));
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path)
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    private Map<String, Object> experimentBody(Object experimentId, Object variantId) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("title", "T", "body", "B", "severity", "INFO",
                "audience", "EXPERIMENT"));
        if (experimentId != null) m.put("experimentId", experimentId.toString());
        if (variantId != null) m.put("variantId", variantId.toString());
        return m;
    }

    @Test
    void create_forAnExperiment_sendsOneSharedNotificationToEveryVariantsMembers() throws Exception {
        experimentFixture();
        List<UserAccount> members = List.of(new UserAccount(), new UserAccount(), new UserAccount());
        when(assignmentRepository.findNotifiableUsers(experiment)).thenReturn(members);
        when(service.createForAll(any(), any(), org.mockito.ArgumentMatchers.eq(members))).thenReturn(3);

        postJson("/api/admin/notifications", experimentBody(experiment.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sent").value(3));

        verify(service, org.mockito.Mockito.times(1)).createForAll(any(), any(), org.mockito.ArgumentMatchers.eq(members));
        verify(service, org.mockito.Mockito.never()).create(any(), any());
        verify(service, org.mockito.Mockito.never()).createForEach(any(), any(), any());
    }

    @Test
    void create_forAnExperimentsVariant_sendsToThatVariantsMembersOnly() throws Exception {
        experimentFixture();
        List<UserAccount> members = List.of(new UserAccount());
        when(assignmentRepository.findNotifiableUsersByVariant(experiment, variant.getId())).thenReturn(members);
        when(service.createForAll(any(), any(), org.mockito.ArgumentMatchers.eq(members))).thenReturn(1);

        postJson("/api/admin/notifications", experimentBody(experiment.getId(), variant.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sent").value(1));

        verify(assignmentRepository, org.mockito.Mockito.never()).findNotifiableUsers(any());
    }

    @Test
    void create_forAnExperimentWithNobody_answers201WithZeroSent() throws Exception {
        experimentFixture();
        when(assignmentRepository.findNotifiableUsers(experiment)).thenReturn(List.of());
        when(service.createForAll(any(), any(), org.mockito.ArgumentMatchers.eq(List.of()))).thenReturn(0);

        postJson("/api/admin/notifications", experimentBody(experiment.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sent").value(0));
    }

    @Test
    void create_forAVariantOfAnotherExperiment_is404() throws Exception {
        experimentFixture();
        Experiment other = new Experiment();
        other.setId(UUID.randomUUID());
        variant.setExperiment(other);

        postJson("/api/admin/notifications", experimentBody(experiment.getId(), variant.getId()))
                .andExpect(status().isNotFound());

        verify(service, org.mockito.Mockito.never()).createForAll(any(), any(), any());
    }

    @Test
    void create_forAnUnknownExperimentOrVariant_is404() throws Exception {
        experimentFixture();

        postJson("/api/admin/notifications", experimentBody(UUID.randomUUID(), null)).andExpect(status().isNotFound());
        postJson("/api/admin/notifications", experimentBody(experiment.getId(), UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void create_forAnExperimentWithoutAnId_is400() throws Exception {
        experimentFixture();

        postJson("/api/admin/notifications", experimentBody(null, null)).andExpect(status().isBadRequest());
    }

    @Test
    void audienceCount_countsTheExperimentsNotifiableMembers() throws Exception {
        experimentFixture();
        when(assignmentRepository.countNotifiableUsers(experiment)).thenReturn(12L);
        when(assignmentRepository.countNotifiableUsersByVariant(experiment, variant.getId())).thenReturn(5L);

        postJson("/api/admin/notifications/audience-count", Map.of("audience", "EXPERIMENT", "experimentId", experiment.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(12));
        postJson("/api/admin/notifications/audience-count", Map.of("audience", "EXPERIMENT",
                "experimentId", experiment.getId().toString(), "variantId", variant.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(5));
    }

    @Test
    void audienceCount_rejectsOtherAudiencesMissingOrUnknownExperimentsAndForeignVariants() throws Exception {
        experimentFixture();
        postJson("/api/admin/notifications/audience-count", Map.of("audience", "CHAT_COMMANDS")).andExpect(status().isBadRequest());
        postJson("/api/admin/notifications/audience-count", Map.of("audience", "EXPERIMENT")).andExpect(status().isBadRequest());
        postJson("/api/admin/notifications/audience-count", Map.of("audience", "EXPERIMENT",
                "experimentId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        Experiment other = new Experiment();
        other.setId(UUID.randomUUID());
        variant.setExperiment(other);
        postJson("/api/admin/notifications/audience-count", Map.of("audience", "EXPERIMENT",
                "experimentId", experiment.getId().toString(), "variantId", variant.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_forTheChatCommandsAudience_tellsEachStreamerWithAnEnabledCommand() throws Exception {
        UserAccount admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(admin));
        List<UserAccount> streamers = List.of(new UserAccount(), new UserAccount());
        when(commandRepository.findActiveUsersWithEnabledCommands()).thenReturn(streamers);
        when(service.createForEach(any(), any(), org.mockito.ArgumentMatchers.eq(streamers))).thenReturn(2);

        mvc.perform(post("/api/admin/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "title", "Commandes", "body", "Bientôt", "severity", "WARNING",
                                "audience", "CHAT_COMMANDS"))))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sent").value(2));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).create(any(), any());
    }

    @Test
    void create_callsService() throws Exception {
        UserAccount admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(admin));

        NotificationDto dto = new NotificationDto(
                UUID.randomUUID(), "Hi", "<p><strong>hello</strong></p>",
                Severity.INFO, null, null, null, Instant.now(), false);
        when(service.create(any(), any())).thenReturn(dto);

        mvc.perform(post("/api/admin/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "title", "Hi",
                                "body", "**hello**",
                                "severity", "INFO"))))
                .andExpect(status().isCreated());

        verify(service).create(any(), any());
    }

    @Test
    void delete_removesNotification() throws Exception {
        UUID id = UUID.randomUUID();
        Notification existing = new Notification();
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        mvc.perform(delete("/api/admin/notifications/{id}", id)
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(repo).delete(existing);
    }

    @Test
    void list_returnsAdminDtosNewestFirstWithoutTheEntity() throws Exception {
        UUID target = UUID.randomUUID();
        AdminNotificationDto targeted = new AdminNotificationDto(UUID.randomUUID(), "T", "**b**", "<p><strong>b</strong></p>",
                Severity.INFO, null, null, null, Instant.now(), "TARGETED", target, "someone", 1);
        AdminNotificationDto broadcast = new AdminNotificationDto(UUID.randomUUID(), "B", "b", "<p>b</p>",
                Severity.WARNING, null, null, null, Instant.now().minusSeconds(60), "BROADCAST", null, null, 0);
        when(service.adminPage(any())).thenReturn(new PageImpl<>(List.of(targeted, broadcast)));

        mvc.perform(get("/api/admin/notifications")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].audience").value("TARGETED"))
                .andExpect(jsonPath("$.content[0].targetUserId").value(target.toString()))
                .andExpect(jsonPath("$.content[0].targetLogin").value("someone"))
                .andExpect(jsonPath("$.content[0].bodyHtml").value("<p><strong>b</strong></p>"))
                .andExpect(jsonPath("$.content[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.content[1].audience").value("BROADCAST"))
                .andExpect(jsonPath("$.content[1].targetLogin").doesNotExist());
    }

    @Test
    void preview_returnsTheRenderedBody() throws Exception {
        when(service.preview("**hi** <script>x</script>"))
                .thenReturn(new NotificationPreviewDto("<p><strong>hi</strong> </p>"));

        mvc.perform(post("/api/admin/notifications/preview")
                        .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("body", "**hi** <script>x</script>"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.containsString("<strong>hi</strong>")))
                .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script"))));
    }
}
