package fr.enimaloc.catapult;

import fr.enimaloc.catapult.service.AdminCclService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Disabled
class CatapultApplicationTests {

    @MockitoBean
    AdminCclService adminCclService;

    @Test
    void contextLoads() {
    }

}
