package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.binding.GetterConfig;

/**
 * A getter's provider is suspended by an emergency stop: detection is frozen for the channel, as with
 * a timeout, so the stop can never read as "game closed" nor fall through to a lower-priority provider.
 */
public class ProviderStoppedException extends DetectionTimeoutException {

    private final GetterConfig.Provider provider;

    public ProviderStoppedException(GetterConfig.Provider provider) {
        super(provider.name(), "is suspended by an emergency stop", null);
        this.provider = provider;
    }

    public GetterConfig.Provider getProvider() {
        return provider;
    }
}
