package fr.enimaloc.catapult.config;

import org.springframework.boot.autoconfigure.context.MessageSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MessageSourceProperties.class)
public class I18nConfiguration {

    @Bean
    public MessageSource messageSource(MessageSourceProperties properties) {
        RecursiveMessageSource messageSource = new RecursiveMessageSource();

        messageSource.setBasenames(properties.getBasename().toArray(String[]::new));
        messageSource.setDefaultEncoding(properties.getEncoding().displayName());
        messageSource.setUseCodeAsDefaultMessage(properties.isUseCodeAsDefaultMessage());

        return messageSource;
    }
}