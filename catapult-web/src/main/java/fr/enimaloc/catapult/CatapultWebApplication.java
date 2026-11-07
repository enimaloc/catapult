package fr.enimaloc.catapult;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * catapult-web. Its members log in through catapult-api (see AuthController), never with a password
 * here: no in-memory user, whose generated password Boot would otherwise log at startup.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CatapultWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatapultWebApplication.class, args);
    }
}
