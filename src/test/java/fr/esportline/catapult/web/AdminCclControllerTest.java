package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.IgdbAgeRatingCategory;
import fr.esportline.catapult.domain.TwitchCclDefinition;
import fr.esportline.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCclControllerTest {

    @Mock
    AdminCclService adminCclService;

    @InjectMocks
    AdminCclController controller;

    @Test
    void index_addsAllCclsAndIgdbCategoriesToModel() {
        TwitchCclDefinition ccl = new TwitchCclDefinition();
        ccl.setId("MatureGame");
        ccl.setName("Mature Game");

        IgdbAgeRatingCategory cat = new IgdbAgeRatingCategory();
        cat.setId(1L);
        cat.setDisplayName("PEGI 18");

        when(adminCclService.getAllCcls()).thenReturn(List.of(ccl));
        when(adminCclService.getAllIgdbCategories()).thenReturn(List.of(cat));

        Model model = new ExtendedModelMap();
        String view = controller.index(model);

        assertThat(view).isEqualTo("admin/ccl");
        assertThat(model.getAttribute("ccls")).isEqualTo(List.of(ccl));
        assertThat(model.getAttribute("igdbCategories")).isEqualTo(List.of(cat));
    }

    @Test
    void refresh_callsRefreshAndRedirects() {
        String view = controller.refresh();
        verify(adminCclService).refreshFromApi();
        assertThat(view).isEqualTo("redirect:/admin/ccl");
    }

    @Test
    void saveMappings_delegatesAndRedirects() {
        String view = controller.saveMappings("MatureGame", Set.of(1L, 2L));
        verify(adminCclService).saveMappings("MatureGame", Set.of(1L, 2L));
        assertThat(view).isEqualTo("redirect:/admin/ccl");
    }

    @Test
    void saveMappings_withNullIds_passesEmptySet() {
        String view = controller.saveMappings("MatureGame", null);
        verify(adminCclService).saveMappings("MatureGame", Set.of());
        assertThat(view).isEqualTo("redirect:/admin/ccl");
    }
}
