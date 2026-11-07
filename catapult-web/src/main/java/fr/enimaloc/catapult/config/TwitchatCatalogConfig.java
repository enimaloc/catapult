package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TwitchatCatalogConfig {

    @Bean
    public TwitchatCatalog twitchatCatalog() {
        return TwitchatCatalog.load();
    }

    @Bean
    public TwitchatEventForms twitchatEventForms(TwitchatCatalog twitchatCatalog) {
        return new TwitchatEventForms(twitchatCatalog);
    }
}
