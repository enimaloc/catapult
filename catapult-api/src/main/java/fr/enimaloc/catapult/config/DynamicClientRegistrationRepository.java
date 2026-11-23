package fr.enimaloc.catapult.config;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The registrations set at startup, plus those whose credentials an admin sets at run time (Bungie): each of these is
 * built again from its supplier on every lookup by id, so that it appears once its credentials are set and is gone
 * (null: the login is refused, its button is not offered) once they are removed. A supplier that fails is no
 * registration, never an error. Iterating (what Spring does while building the security filter chain, to list the
 * login links) only yields the fixed registrations: the run-time ones are secondary links, never a login entry
 * point, and building the chain must not read the database.
 */
public class DynamicClientRegistrationRepository implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final InMemoryClientRegistrationRepository fixed;
    private final Map<String, Supplier<Optional<ClientRegistration>>> dynamic;

    public DynamicClientRegistrationRepository(List<ClientRegistration> fixed,
                                               Map<String, Supplier<Optional<ClientRegistration>>> dynamic) {
        this.fixed = new InMemoryClientRegistrationRepository(fixed);
        this.dynamic = Map.copyOf(dynamic);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        Supplier<Optional<ClientRegistration>> supplier = registrationId == null ? null : dynamic.get(registrationId);
        if (supplier != null) {
            try {
                return supplier.get().orElse(null);
            } catch (RuntimeException e) {
                // Credentials unreadable now (database down…): that login is off, the others keep working
                return null;
            }
        }
        return fixed.findByRegistrationId(registrationId);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        List<ClientRegistration> all = new ArrayList<>();
        fixed.forEach(all::add);
        return all.iterator();
    }
}
