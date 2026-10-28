package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameDetailsRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadMode;
import fr.enimaloc.catapult.repository.access.AlphaInviteRedemptionRepository;
import fr.enimaloc.catapult.repository.access.AlphaInviteRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatEventActionRepository;
import fr.enimaloc.catapult.service.account.SeenFeatureService;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.meta.MetaTagService;
import fr.enimaloc.catapult.service.social.SocialLinkService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The data the budgeted routes read: three callers ({@code owner}, the {@code visitor} who moderates the owner's
 * channel, {@code admin}) and {@value #SMALL} elements of every collection a budgeted route shows, which
 * {@link #grow} takes to {@value #LARGE}. A route whose SQL count moves between the two sizes reads its collection
 * one element at a time.
 *
 * <p>Collections: the owner's game bindings, custom bindings, notifications, invite redemptions, Twitchat event
 * actions, chat commands and seen features; the channels the owner moderates (members too, all named
 * {@value #PREFIX}…); the {@value #PREFIX} experiments, the first one's rules and assignments; the meta tags; the social links. Only
 * the notification channels stay as they are: there are only two.
 */
@TestComponent
public class BudgetFixtures {

    static final int SMALL = 2;
    static final int LARGE = 5;
    static final String PREFIX = "budget";
    /** The Steam app the owner plays: their first game binding's. */
    static final String STEAM_APP_ID = "990000";
    /** The IGDB game of {@link #STEAM_APP_ID}, its details cached. */
    static final String IGDB_ID = "1";
    /** The DTDD title {@link #IGDB_ID} is mapped to, its topics cached. */
    static final long DTDD_ID = 1;
    /** The file served at {@code /.well-known/} + this. */
    static final String WELL_KNOWN_PATH = "security.txt";

    private final TransactionOperations transactions;
    private final UserAccountRepository accounts;
    private final GameBindingRepository gameBindings;
    private final CustomBindingRepository customBindings;
    private final NotificationService notifications;
    private final NotificationDeliveryService deliveries;
    private final AlphaInviteRepository invites;
    private final AlphaInviteRedemptionRepository redemptions;
    private final ExperimentRepository experiments;
    private final ExperimentAssignmentRepository assignments;
    private final TwitchatEventActionRepository twitchatActions;
    private final ChatCommandDefinitionRepository chatCommands;
    private final SeenFeatureService seenFeatures;
    private final MetaTagService metaTags;
    private final SocialLinkService socialLinks;
    private final UserApiTokenService userApiTokens;
    private final WidgetTokenService widgetTokens;
    private final DtddGameCacheRepository dtddGames;
    private final DtddGameMappingRepository dtddMappings;
    private final DtddTopicsCacheRepository dtddTopics;
    private final IgdbGameDetailsRepository igdbDetails;
    private final WellKnownFileService wellKnownFiles;
    private final GameStateService gameStates;
    private final TwitchatWidgetSettingsService widgetSettings;

    public BudgetFixtures(
            TransactionOperations transactions,
            UserAccountRepository accounts,
            GameBindingRepository gameBindings,
            CustomBindingRepository customBindings,
            NotificationService notifications,
            NotificationDeliveryService deliveries,
            AlphaInviteRepository invites,
            AlphaInviteRedemptionRepository redemptions,
            ExperimentRepository experiments,
            ExperimentAssignmentRepository assignments,
            TwitchatEventActionRepository twitchatActions,
            ChatCommandDefinitionRepository chatCommands,
            SeenFeatureService seenFeatures,
            MetaTagService metaTags,
            SocialLinkService socialLinks,
            UserApiTokenService userApiTokens,
            WidgetTokenService widgetTokens,
            DtddGameCacheRepository dtddGames,
            DtddGameMappingRepository dtddMappings,
            DtddTopicsCacheRepository dtddTopics,
            IgdbGameDetailsRepository igdbDetails,
            WellKnownFileService wellKnownFiles,
            GameStateService gameStates,
            TwitchatWidgetSettingsService widgetSettings) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.gameBindings = gameBindings;
        this.customBindings = customBindings;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.invites = invites;
        this.redemptions = redemptions;
        this.experiments = experiments;
        this.assignments = assignments;
        this.twitchatActions = twitchatActions;
        this.chatCommands = chatCommands;
        this.seenFeatures = seenFeatures;
        this.metaTags = metaTags;
        this.socialLinks = socialLinks;
        this.userApiTokens = userApiTokens;
        this.widgetTokens = widgetTokens;
        this.dtddGames = dtddGames;
        this.dtddMappings = dtddMappings;
        this.dtddTopics = dtddTopics;
        this.igdbDetails = igdbDetails;
        this.wellKnownFiles = wellKnownFiles;
        this.gameStates = gameStates;
        this.widgetSettings = widgetSettings;
    }

    /**
     * What the rows address: the callers, the owner's widget token and personal API token, and the ids that stand
     * in for {@code {bindingId}} (the owner's first game binding) and {@code {id}} (the first experiment).
     * {@code channelTwitchIds} are the Twitch ids of every channel the owner moderates at {@value #LARGE}, known from
     * the start so that Twitch answers the same however many of them have an account yet.
     */
    public record Accounts(
            UserAccount owner,
            UserAccount visitor,
            UserAccount admin,
            UUID widgetToken,
            String ptoken,
            UUID bindingId,
            UUID experimentId,
            List<String> channelTwitchIds) {}

    /**
     * The callers and {@value #SMALL} elements of each collection; the owner plays {@link #STEAM_APP_ID}, whose
     * IGDB details, DTDD title, mapping and topics are cached, their Twitchat widget is on, and a well-known file is
     * served.
     */
    public Accounts create() {
        Accounts created = transactions.execute(status -> {
            UserAccount owner = account(PREFIX + "-owner");
            UserAccount visitor = account(PREFIX + "-visitor");
            UserAccount admin = account(PREFIX + "-admin");
            openCustomBindingsTo(owner);
            deliveries.replaceChannels(owner, EnumSet.allOf(NotificationChannel.class));
            widgetSettings.updateSettings(owner, true, "127.0.0.1", 4455, "budget-obs-password");
            AlphaInvite invite = new AlphaInvite();
            invite.setOwner(owner);
            invite.setCode("budgetinvite");
            invites.save(invite);
            Experiment experiment = experiment(0);
            List<String> channelTwitchIds =
                    IntStream.range(0, LARGE).mapToObj(BudgetFixtures::channelTwitchId).toList();
            // the binding id is only known once the bindings are saved
            Accounts partial = new Accounts(owner, visitor, admin, widgetTokens.getOrCreate(owner),
                    userApiTokens.regenerate(owner, ApiTokenScope.FULL).token(), null, experiment.getId(), channelTwitchIds);
            add(partial, 0, SMALL);
            GameBinding first = gameBindings.findAll().stream()
                    .filter(b -> b.getSourceName().equals(gameName(0)))
                    .findFirst()
                    .orElseThrow();
            lookups(Instant.now());
            return new Accounts(owner, visitor, admin, partial.widgetToken(), partial.ptoken(), first.getId(),
                    experiment.getId(), channelTwitchIds);
        });
        gameStates.updateState(
                created.owner(), new DetectedGame(STEAM_APP_ID, GameBinding.SourceType.STEAM, gameName(0)));
        return created;
    }

    /** What the public lookups by id serve without asking IGDB or DTDD: their caches, and the well-known file. */
    private void lookups(Instant now) {
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId(IGDB_ID);
        details.setSlug(PREFIX + "-game");
        details.setSummary("Budget game summary");
        details.setFetchedAt(now);
        igdbDetails.save(details);
        dtddGames.save(new DtddGameCache(DTDD_ID, "Budget game 0", PREFIX + "-game", "Video Game", null, now));
        DtddGameMapping mapping = new DtddGameMapping();
        mapping.setIgdbId(IGDB_ID);
        mapping.setDtddId(DTDD_ID);
        mapping.setConfidence(0.9);
        mapping.setResolvedAt(now);
        dtddMappings.save(mapping);
        dtddTopics.save(new DtddTopicsCache(DTDD_ID, List.of("budget-yes"), List.of("budget-no"), List.of(), now));
        wellKnownFiles.create(new WellKnownFileRequest(
                WellKnownLocation.WELL_KNOWN, WELL_KNOWN_PATH, 200, List.of(), "text/plain", "Contact: budget"));
    }

    /** Takes every collection from {@value #SMALL} to {@value #LARGE} elements. */
    public void grow(Accounts created) {
        transactions.executeWithoutResult(status -> add(created, SMALL, LARGE));
    }

    /** Elements {@code from} (included) to {@code to} (excluded) of every collection. */
    private void add(Accounts created, int from, int to) {
        UserAccount owner = accounts.findById(created.owner().getId()).orElseThrow();
        UserAccount admin = accounts.findById(created.admin().getId()).orElseThrow();
        AlphaInvite invite = invites.findByOwner(owner).orElseThrow();
        Experiment target = experiments.findById(created.experimentId()).orElseThrow();
        Instant now = Instant.now();
        for (int i = from; i < to; i++) {
            gameBindings.save(gameBinding(owner, i));
            customBindings.save(customBinding(owner, i));
            notifications.create(new NotificationService.CreateRequest(
                    "Budget notification " + i, "Body " + i, Severity.INFO, null, null, null, owner.getId()), admin);
            AlphaInviteRedemption redemption = new AlphaInviteRedemption();
            redemption.setInvite(invite);
            redemption.setInviteeTwitchId(PREFIX + "-invitee-" + i);
            redemptions.save(redemption);
            twitchatActions.save(twitchatAction(owner, i, now));
            chatCommands.save(chatCommand(owner, i));
            seenFeatures.markSeen(owner.getId(), PREFIX + ".feature-" + i);
            metaTags.create(new MetaTagRequest(MetaTagAttribute.NAME, PREFIX + "-key-" + i, "Budget content " + i));
            socialLinks.create(new SocialLinkRequest(SocialPlatform.OTHER, "Budget " + i, "https://budget.example/" + i, true));
            UserAccount channel = account(PREFIX + "-channel-" + i, channelTwitchId(i));
            if (i > 0) {
                experiment(i);
            }
            ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
            rule.setExperiment(target);
            rule.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
            rule.setPriority(i);
            rule.setPercentage(10 * (i + 1));
            target.getRules().add(rule);
            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(target);
            assignment.setVariant(target.getVariants().get(i % target.getVariants().size()));
            assignment.setUser(channel);
            assignments.save(assignment);
        }
        experiments.save(target);
    }

    private UserAccount account(String username) {
        return account(username, username + "-twitch");
    }

    private UserAccount account(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return accounts.save(account);
    }

    private static String channelTwitchId(int i) {
        return PREFIX + "-channel-" + i + "-twitch";
    }

    private static String gameName(int i) {
        return "Budget game " + i;
    }

    private static GameBinding gameBinding(UserAccount owner, int i) {
        GameBinding binding = new GameBinding();
        binding.setUser(owner);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId(String.valueOf(Integer.parseInt(STEAM_APP_ID) + i));
        binding.setSourceName(gameName(i));
        binding.setTwitchGameId(String.valueOf(880_000 + i));
        binding.setTwitchGameName("Budget category " + i);
        binding.setStatus(GameBinding.Status.MANUAL);
        binding.setCcls(new HashSet<>(Set.of("ProfanityVulgarity")));
        binding.setTws(new HashSet<>(Set.of(PREFIX + "-tw")));
        return binding;
    }

    private static CustomBinding customBinding(UserAccount owner, int i) {
        CustomBinding binding = new CustomBinding();
        binding.setUser(owner);
        binding.setName(PREFIX + "-custom-" + i);
        binding.setTwitchGameId(String.valueOf(770_000 + i));
        binding.setTwitchGameName("Budget custom category " + i);
        binding.setCcls(new HashSet<>(Set.of("ProfanityVulgarity")));
        binding.setTws(new HashSet<>(Set.of(PREFIX + "-tw")));
        return binding;
    }

    private static TwitchatEventAction twitchatAction(UserAccount owner, int i, Instant now) {
        TwitchatEventAction action = new TwitchatEventAction();
        action.setId(UUID.randomUUID());
        action.setUser(owner);
        action.setEventType(TwitchatNotificationEventType.STREAM_STARTED);
        // past the positions a default payload may take
        action.setPosition(100 + i);
        action.setAction(PREFIX + "-action-" + i);
        action.setMode(TwitchatPayloadMode.RAW);
        action.setDataJson("{}");
        action.setEnabled(true);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        return action;
    }

    private static ChatCommandDefinition chatCommand(UserAccount owner, int i) {
        ChatCommandDefinition command = new ChatCommandDefinition();
        command.setUser(owner);
        command.setName(PREFIX + i);
        command.setTemplate("Budget reply " + i);
        command.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        command.setEnabled(true);
        return command;
    }

    /** The {@code i}th {@value #PREFIX} experiment, a draft with a control and a treatment variant. */
    private Experiment experiment(int i) {
        Experiment experiment = new Experiment();
        experiment.setKey(PREFIX + ".experiment-" + i);
        experiment.setName("Budget experiment " + i);
        List<ExperimentVariant> variants = new ArrayList<>();
        for (String key : List.of("control", "treatment")) {
            ExperimentVariant variant = new ExperimentVariant();
            variant.setExperiment(experiment);
            variant.setKey(key);
            variant.setName(key);
            variant.setControl("control".equals(key));
            variant.setInternalId(variants.size());
            variants.add(variant);
        }
        experiment.getVariants().addAll(variants);
        return experiments.save(experiment);
    }

    /**
     * Lets the owner into the {@value CustomBindingService#EXPERIMENT_KEY} experiment, as the access matrix does:
     * makes it ACTIVE and assigns them its {@code enabled} variant.
     */
    private void openCustomBindingsTo(UserAccount account) {
        Experiment experiment = experiments.findByKey(CustomBindingService.EXPERIMENT_KEY).orElseThrow();
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiments.save(experiment);
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(experiment.getVariants().stream()
                .filter(v -> "enabled".equals(v.getKey()))
                .findFirst()
                .orElseThrow());
        assignment.setUser(account);
        assignments.save(assignment);
    }
}
