package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The admin data tab's writes, forwarded to catapult-api with its status, which admin-data.js
 * turns into a message (400 invalid value, 404 gone, 409 still referenced). A row's id travels
 * as a parameter: catapult-api's ids are already percent-encoded, a path would decode them.
 */
@RestController
@RequestMapping("/admin/data/{repo}")
@RequiredArgsConstructor
public class AdminDataController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/create")
    public ResponseEntity<Void> create(@PathVariable String repo, @RequestBody Map<String, String> fields) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminCreateDataRow(repo, fields)).build();
    }

    @PostMapping("/update")
    public ResponseEntity<Void> update(@PathVariable String repo, @RequestParam String id,
                                       @RequestBody Map<String, String> fields) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminUpdateDataRow(repo, id, fields)).build();
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@PathVariable String repo, @RequestParam String id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteDataRow(repo, id)).build();
    }
}
