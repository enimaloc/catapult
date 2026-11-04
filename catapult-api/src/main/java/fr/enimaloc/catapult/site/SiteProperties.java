package fr.enimaloc.catapult.site;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code catapult.site.*}: how this cluster tells whether it is the active one. */
@Component
@ConfigurationProperties("catapult.site")
@Getter
@Setter
public class SiteProperties {

    /** This site's public IP; empty means the guard is not configured and the instance is always active. */
    private String publicIp = "";

    /** The name whose A records say which site is active. */
    private String dnsName = "catapult.enimaloc.fr";

    private long checkIntervalMs = 20000;

    /** How long a failed DNS resolution keeps the last known state. */
    private long graceMs = 150000;

    public boolean configured() {
        return publicIp != null && !publicIp.isBlank();
    }
}
