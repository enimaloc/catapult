package fr.esportline.catapult;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CatapultApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatapultApplication.class, args);
    }

}
