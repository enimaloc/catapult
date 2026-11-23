package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData;
import fr.enimaloc.catapult.common.dto.admin.BungieCredentialsRequest;
import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Bungie.net application (OAuth client id and secret, API key) an admin sets: read (the client id and the key
 * masked, never the secret), set (400 for a malformed value, 409 when crossing another write), removed. Without it
 * the Bungie source and login are off.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/bungie-credentials")
@RequiredArgsConstructor
public class ApiAdminBungieCredentialsController {

    private final BungieCredentialsService credentials;

    @GetMapping
    public BungieCredentialsData page() {
        return credentials.page();
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@RequestBody BungieCredentialsRequest body) {
        credentials.save(body.clientId(), body.clientSecret(), body.apiKey());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        credentials.delete();
    }
}
