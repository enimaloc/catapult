package fr.enimaloc.catapult.site;

import org.springframework.context.ApplicationEvent;

/** Published on every transition between active and standby. */
public class SiteRoleChangedEvent extends ApplicationEvent {

    private final boolean active;

    public SiteRoleChangedEvent(Object source, boolean active) {
        super(source);
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }
}
