package fr.enimaloc.catapult.site;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test doubles for {@link SiteRole}. */
public final class SiteRoles {

    private SiteRoles() {}

    /** A role that reports the site as active. */
    public static SiteRole active() {
        SiteRole role = mock(SiteRole.class);
        when(role.isActive()).thenReturn(true);
        return role;
    }

    /** A role that reports the site as standby. */
    public static SiteRole standby() {
        SiteRole role = mock(SiteRole.class);
        when(role.isActive()).thenReturn(false);
        return role;
    }
}
