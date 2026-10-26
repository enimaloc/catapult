package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The {@code perf} profile's routes, for the load and browser runs of a throwaway stack: a test login, the seeded
 * accounts, a simulated game change. Open to anyone ({@link PerfSecurityConfig}); absent outside {@code perf}.
 */
@RestController
@Profile("perf")
@RequestMapping(PerfController.PATH)
public class PerfController {

    public static final String PATH = ApiPaths.INTERNAL + "/perf";

    private final PerfService perf;

    public PerfController(PerfService perf) {
        this.perf = perf;
    }

    @PostMapping("/login")
    public PerfService.PerfToken login(@RequestParam String username) {
        return perf.login(username);
    }

    @GetMapping("/accounts")
    public List<PerfService.PerfAccount> accounts() {
        return perf.accounts();
    }

    @PostMapping("/users/{username}/game")
    public ResponseEntity<Void> playGame(@PathVariable String username, @RequestParam String name) {
        perf.playGame(username, name);
        return ResponseEntity.noContent().build();
    }
}
