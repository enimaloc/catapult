package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AdminProviderIgdbQueryRequest;
import fr.enimaloc.catapult.service.provider.IgdbRawQueries;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/igdb")
@RequiredArgsConstructor
public class ApiAdminProviderIgdbController {

    private final IgdbRawQueries queries;

    @PostMapping("/query")
    public RawProviderResponseSupport.RawProviderResponse query(@RequestBody AdminProviderIgdbQueryRequest body) {
        if (!body.endpoint().matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endpoint: " + body.endpoint());
        }
        return queries.query(body);
    }
}
