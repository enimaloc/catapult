package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaDebugKeys;
import fr.enimaloc.catapult.service.battlenet.BattleNetDebugKeys;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.debug.SessionDebug.LinkedAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Set;

/**
 * The debug recording of the bot sessions, one per service: how each one knows a member (from its friend link) and
 * where it is connected to.
 */
@Configuration
public class SessionDebugConfig {

    @Bean
    public SessionDebug epicSessionDebug(DebugMemberRegistry registry, DebugExchangeRecorder recorder,
                                         EpicFriendLinkRepository links,
                                         @Value("${epic.connect-url:wss://connect.ol.epicgames.com}") String url) {
        return new SessionDebug(registry, recorder, "epic", URI.create(url), userId -> links.findByUserId(userId)
                .filter(link -> link.getEpicAccountId() != null)
                .map(link -> new LinkedAccount(link.getServiceAccount().getId(), Set.of(link.getEpicAccountId()))));
    }

    @Bean
    public SessionDebug battleNetSessionDebug(DebugMemberRegistry registry, DebugExchangeRecorder recorder,
                                              BattleNetFriendLinkRepository links,
                                              @Value("${battlenet.bgs-host:eu.actual.battle.net}") String host) {
        return new SessionDebug(registry, recorder, "battlenet", URI.create("bgs://" + host),
                userId -> links.findByUserId(userId)
                        .map(link -> new LinkedAccount(link.getServiceAccount().getId(),
                                BattleNetDebugKeys.of(link))));
    }

    @Bean
    public SessionDebug ankamaSessionDebug(DebugMemberRegistry registry, DebugExchangeRecorder recorder,
                                           AnkamaFriendLinkRepository links,
                                           @Value("${ankama.chat-host:zaap-chat.ankama.com}") String host) {
        return new SessionDebug(registry, recorder, "ankama", URI.create("tls://" + host),
                userId -> links.findByUserId(userId)
                        .map(link -> new LinkedAccount(link.getServiceAccount().getId(), AnkamaDebugKeys.of(link))));
    }
}
