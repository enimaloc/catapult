package fr.enimaloc.catapult;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class CatapultWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatapultWebApplication.class, args);
    }
}
