package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData;
import fr.enimaloc.catapult.common.dto.admin.OsuCredentialsRequest;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
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
 * The osu! OAuth application (client id and secret) an admin sets: read (the client id, never the secret), set (400
 * for a malformed value, 409 when crossing another write), removed. Without it the osu! source and login are off.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/osu-credentials")
@RequiredArgsConstructor
public class ApiAdminOsuCredentialsController {

    private final OsuCredentialsService credentials;

    @GetMapping
    public OsuCredentialsData page() {
        return credentials.page();
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@RequestBody OsuCredentialsRequest body) {
        credentials.save(body.clientId(), body.clientSecret());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        credentials.delete();
    }
}
