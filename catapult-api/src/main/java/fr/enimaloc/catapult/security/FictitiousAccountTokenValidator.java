package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.simulation.SimulationGate;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Outside the {@code mock} profile, refuses the JWT of a fictitious account (its {@code twitchId} claim starts with
 * {@code m}, which no real numeric Twitch id does): only the profile's fictitious login issues one, so a token left
 * over from a {@code mock} run, a fictitious admin's included, dies at its first request once the server runs
 * without the profile.
 */
public class FictitiousAccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error FICTITIOUS = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "A fictitious account's token is only valid under the mock profile", null);

    private final boolean mockProfile;

    public FictitiousAccountTokenValidator(Environment environment) {
        this.mockProfile = environment.acceptsProfiles(Profiles.of("mock"));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!mockProfile && SimulationGate.isFictitiousTwitchId(token.getClaimAsString("twitchId"))) {
            return OAuth2TokenValidatorResult.failure(FICTITIOUS);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
