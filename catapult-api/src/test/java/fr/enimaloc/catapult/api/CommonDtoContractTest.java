package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.admin.ApiAdminDtddMappingController;
import fr.enimaloc.catapult.api.admin.ApiAdminExperimentsController;
import fr.enimaloc.catapult.api.admin.ApiAdminFlagsController;
import fr.enimaloc.catapult.api.admin.ApiAdminMembersController;
import fr.enimaloc.catapult.api.channel.ApiTwitchatWidgetController;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.channel.ConfigResponse;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import fr.enimaloc.catapult.repository.account.UserGroupRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.AdminMigrationService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What catapult-api answers, once on the wire, reads back as the catapult-common type catapult-web
 * deserializes it into, nothing lost: these endpoints used to answer JPA entities or look-alike
 * records whose fields catapult-web's DTOs didn't match.
 */
class CommonDtoContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static <T> T roundTrip(Object produced, Class<T> consumed) {
        return JSON.readValue(JSON.writeValueAsString(produced), consumed);
    }

    @Test
    void adminMembers_readAsAdminMembersPage() {
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        StreamStateService streams = mock(StreamStateService.class);
        Environment environment = mock(Environment.class);
        UserAccount member = new UserAccount();
        member.setId(UUID.randomUUID());
        member.setTwitchId("42");
        member.setTwitchUsername("streamer");
        member.setProfileImageUrl("https://img/streamer");
        member.setSteamId("7656");
        member.setWidgetToken(UUID.randomUUID());
        member.setCreatedAt(Instant.parse("2026-10-16T10:00:00Z"));
        when(accounts.findAll()).thenReturn(List.of(member));
        when(streams.isLive(member)).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        ApiAdminMembersController controller = new ApiAdminMembersController(accounts, streams,
                mock(AccountService.class), mock(BotToggleService.class), mock(AdminMigrationService.class),
                environment, mock(UserFlagRepository.class), mock(UserGroupRepository.class));

        AdminMembersPage produced = controller.page();
        String json = JSON.writeValueAsString(produced);
        AdminMembersPage read = JSON.readValue(json, AdminMembersPage.class);

        assertThat(read).isEqualTo(produced);
        assertThat(read.members().getFirst().twitchUsername()).isEqualTo("streamer");
        assertThat(read.isLive(member.getId())).isTrue();
        assertThat(json).doesNotContain(member.getWidgetToken().toString());
    }

    @Test
    void dtddProposals_readAsDtddProposalDtos() {
        DtddMappingProposalRepository proposals = mock(DtddMappingProposalRepository.class);
        UserAccount proposer = new UserAccount();
        proposer.setTwitchUsername("viewer");
        DtddMappingProposal proposal = new DtddMappingProposal();
        proposal.setId(UUID.randomUUID());
        proposal.setIgdbId("100");
        proposal.setProposedDtddId(1287L);
        proposal.setProposer(proposer);
        proposal.setReason("same game");
        proposal.setStatus(DtddMappingProposal.Status.PENDING);
        proposal.setCreatedAt(Instant.parse("2026-10-16T10:00:00Z"));
        when(proposals.findByStatusOrderByCreatedAtAsc(DtddMappingProposal.Status.PENDING)).thenReturn(List.of(proposal));
        ApiAdminDtddMappingController controller = new ApiAdminDtddMappingController(proposals,
                mock(DtddGameMappingRepository.class), mock(DtddTopicsCacheRepository.class),
                mock(fr.enimaloc.catapult.service.dtdd.DtddMappingEvents.class), mock(ApiUserResolver.class));

        List<DtddProposalDto> produced = controller.pending("PENDING");
        List<DtddProposalDto> read = JSON.readValue(JSON.writeValueAsString(produced), new TypeReference<>() {});

        assertThat(read).isEqualTo(produced);
        assertThat(read.getFirst().proposer().twitchUsername()).isEqualTo("viewer");
    }

    @Test
    void twitchatWidgetConfig_readAsConfigResponse_branchIncluded() {
        TwitchatWidgetSettingsRepository settingsRepository = mock(TwitchatWidgetSettingsRepository.class);
        WidgetTokenService tokens = mock(WidgetTokenService.class);
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setEnabled(true);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setTwitchatBranch("beta");
        when(tokens.resolve(token)).thenReturn(Optional.of(owner));
        when(settingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));
        ApiTwitchatWidgetController controller = new ApiTwitchatWidgetController(settingsRepository, tokens,
                mock(TokenEncryptionService.class), mock(TwitchatActionExecutor.class));

        ConfigResponse produced = controller.config(token).getBody();

        assertThat(roundTrip(produced, ConfigResponse.class))
                .isEqualTo(new ConfigResponse("127.0.0.1", 4455, null, "beta"));
    }

    @Test
    void adminExperiments_readAsSummaryAndSettingsDtos() {
        ExperimentRepository experiments = mock(ExperimentRepository.class);
        ExperimentOverrideRepository overrides = mock(ExperimentOverrideRepository.class);
        Experiment experiment = new Experiment();
        experiment.setId(UUID.randomUUID());
        experiment.setKey("new-dashboard");
        experiment.setName("New dashboard");
        ExperimentVariant control = new ExperimentVariant();
        control.setId(UUID.randomUUID());
        control.setKey("old");
        control.setName("Old");
        control.setControl(true);
        experiment.setVariants(new java.util.ArrayList<>(List.of(control)));
        when(experiments.findAll()).thenReturn(List.of(experiment));
        when(experiments.findById(experiment.getId())).thenReturn(Optional.of(experiment));
        when(overrides.findByExperimentOrderByPriorityAsc(experiment)).thenReturn(List.of());
        ApiAdminExperimentsController controller = new ApiAdminExperimentsController(experiments,
                null, null, null, null, null, overrides, null, null, null, null);

        List<ExperimentSummaryDto> summaries = controller.list();
        ExperimentSettingsDto settings = controller.settings(experiment.getId());

        assertThat(JSON.readValue(JSON.writeValueAsString(summaries), new TypeReference<List<ExperimentSummaryDto>>() {}))
                .isEqualTo(summaries);
        assertThat(roundTrip(settings, ExperimentSettingsDto.class)).isEqualTo(settings);
        assertThat(settings.variants().getFirst().control()).isTrue();
    }

    @Test
    void adminFlags_readAsFlagKeyDtos() {
        UserFlagRepository flags = mock(UserFlagRepository.class);
        when(flags.findDistinctKeyValues()).thenReturn(List.of(
                new Object[] {"beta", "on"}, new Object[] {"tester", null}));

        List<FlagKeyDto> produced = new ApiAdminFlagsController(flags).list();

        assertThat(JSON.readValue(JSON.writeValueAsString(produced), new TypeReference<List<FlagKeyDto>>() {}))
                .containsExactly(new FlagKeyDto("beta", List.of("on")), new FlagKeyDto("tester", List.of()));
    }
}
