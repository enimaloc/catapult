package fr.enimaloc.management;

import fr.enimaloc.catapult.config.DevAppHeaderFilter;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * DevAppHeaderFilter on the management port too, whose own context sees none of the main one's
 * filters. Listed in META-INF/spring/…ManagementContextConfiguration.imports and kept outside
 * fr.enimaloc.catapult, which the application scans: there it would be loaded in the main context.
 */
@ManagementContextConfiguration(ManagementContextType.CHILD)
@Profile("dev")
public class DevAppHeaderManagementConfiguration {
    @Bean
    DevAppHeaderFilter devAppHeaderFilter() {
        return new DevAppHeaderFilter();
    }
}
