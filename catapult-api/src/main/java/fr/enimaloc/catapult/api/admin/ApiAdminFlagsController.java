package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The targeting tags members carry, for the admin page's override form to propose. */
@RestController
@RequestMapping("/api/admin/flags")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminFlagsController {
    private final UserFlagRepository flags;

    @GetMapping
    @Transactional(readOnly = true)
    public List<FlagKeyDto> list() {
        // rows arrive ordered by key then value, so a linked map keeps both orders
        Map<String, List<String>> valuesByKey = new LinkedHashMap<>();
        for (Object[] row : flags.findDistinctKeyValues()) {
            List<String> values = valuesByKey.computeIfAbsent((String) row[0], k -> new ArrayList<>());
            if (row[1] != null) values.add((String) row[1]);
        }
        return valuesByKey.entrySet().stream().map(e -> new FlagKeyDto(e.getKey(), e.getValue())).toList();
    }
}
