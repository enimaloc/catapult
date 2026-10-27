package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.service.account.AdminMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The targeting tags members carry, for the admin page's override form to propose. */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/flags")
@RequiredArgsConstructor
public class ApiAdminFlagsController {
    private final AdminMemberService members;

    @GetMapping
    public List<FlagKeyDto> list() {
        return members.flagKeys();
    }
}
