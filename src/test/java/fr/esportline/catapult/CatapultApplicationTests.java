package fr.esportline.catapult;

import fr.esportline.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class CatapultApplicationTests {

    @MockitoBean
    AdminCclService adminCclService;

    @Test
    void contextLoads() {
    }

}
