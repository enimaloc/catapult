package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminExperimentsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminExperimentsControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000043");
    static final String BASE = "/admin/experiments/" + ID;

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void lifecycleActions_relayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminExperimentAction(ID, "activate")).thenReturn(204);
        when(apiService.adminExperimentAction(ID, "pause")).thenReturn(409);
        when(apiService.adminExperimentAction(ID, "end")).thenReturn(204);
        when(apiService.adminExperimentAction(ID, "reassign")).thenReturn(404);

        mvc.perform(admin(post(BASE + "/activate"), "{}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/pause"), "{}")).andExpect(status().isConflict());
        mvc.perform(admin(post(BASE + "/end"), "{}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/reassign"), "{}")).andExpect(status().isNotFound());
    }

    @Test
    void unknownAction_is404_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/explode"), "{}")).andExpect(status().isNotFound());
        verify(apiService, never()).adminExperimentAction(any(), any());
    }

    @Test
    void weights_areRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminExperimentWeights(ID, Map.of(ID, 30, OTHER, 70))).thenReturn(204);

        mvc.perform(admin(post(BASE + "/weights"), "{\"" + ID + "\":30,\"" + OTHER + "\":70}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void weights_summingPastIntRange_areNotMistakenForZero() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        // 2 * Integer.MAX_VALUE + 2 wraps to 0 as an int
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000044");
        when(apiService.adminExperimentWeights(ID, Map.of(ID, Integer.MAX_VALUE, OTHER, Integer.MAX_VALUE, third, 2))).thenReturn(204);
        mvc.perform(admin(post(BASE + "/weights"), "{\"" + ID + "\":2147483647,\"" + OTHER + "\":2147483647,\"" + third + "\":2}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void weights_negativeOrAllZero_are400() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/weights"), "{\"" + ID + "\":0,\"" + OTHER + "\":0}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/weights"), "{\"" + ID + "\":-1,\"" + OTHER + "\":5}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminExperimentWeights(any(), any());
    }

    @Test
    void randomRule_mapsThePercentage() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentRule(ID, new ExperimentRuleRequest("RANDOM", 0, 25, null, null, null))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"RANDOM\",\"percentage\":25}")).andExpect(status().isCreated());
    }

    @Test
    void randomRule_percentageOutOfRange_is400() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"RANDOM\",\"percentage\":101}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"RANDOM\",\"percentage\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"RANDOM\"}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddExperimentRule(any(), any());
    }

    @Test
    void groupRule_isAnInGroupAttribute() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentRule(ID, new ExperimentRuleRequest("GROUP", 0, null, "group", "in", "beta"))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"GROUP\",\"groupKey\":\" beta \"}")).andExpect(status().isCreated());
    }

    @Test
    void attributeRule_isRelayedAsGiven() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentRule(ID, new ExperimentRuleRequest("ATTRIBUTE", 0, null, "plan", "eq", "pro"))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/rules"),
                "{\"type\":\"ATTRIBUTE\",\"attributeKey\":\"plan\",\"attributeOperator\":\"eq\",\"attributeValue\":\"pro\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void experimentRule_isAnEqOnTheOtherExperimentsVariant() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentRule(ID, new ExperimentRuleRequest("EXPERIMENT", 0, null, "exp-a", "eq", "v1"))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"EXPERIMENT\",\"experimentKey\":\"exp-a\",\"variantKey\":\"v1\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void manualRule_carriesNothing() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentRule(ID, new ExperimentRuleRequest("MANUAL", 0, null, null, null, null))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"MANUAL\"}")).andExpect(status().isCreated());
    }

    @Test
    void rule_missingItsFields_orUnknown_is400() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"GROUP\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"EXPERIMENT\",\"experimentKey\":\"a\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"ATTRIBUTE\",\"attributeKey\":\"a\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"),
                "{\"type\":\"ATTRIBUTE\",\"attributeKey\":\"a\",\"attributeOperator\":\"gt\",\"attributeValue\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{\"type\":\"NOPE\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/rules"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddExperimentRule(any(), any());
    }

    @Test
    void deletes_relayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteExperimentRule(ID, OTHER)).thenReturn(204);
        when(apiService.adminDeleteExperimentOverride(ID, OTHER)).thenReturn(404);

        mvc.perform(admin(post(BASE + "/rules/" + OTHER + "/delete"), "{}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/overrides/" + OTHER + "/delete"), "{}")).andExpect(status().isNotFound());
    }

    @Test
    void accountOverride_isAUserOverride() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentOverride(ID,
                new ExperimentOverrideRequest("USER", "FORCE_INCLUDE", 3, "alice", null, null, null, null))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/overrides"),
                "{\"target\":\"account\",\"twitchUsername\":\" alice \",\"action\":\"FORCE_INCLUDE\",\"priority\":3}"))
                .andExpect(status().isCreated());
    }

    @Test
    void tagOverride_withAValue_isAnEqOnTheFlag() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentOverride(ID,
                new ExperimentOverrideRequest("ATTRIBUTE", "FORCE_EXCLUDE", 0, null, "flag:vip", "eq", "on", null))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/overrides"),
                "{\"target\":\"tag\",\"tagKey\":\" vip \",\"tagValue\":\" on \",\"action\":\"FORCE_EXCLUDE\",\"priority\":0}"))
                .andExpect(status().isCreated());
    }

    @Test
    void tagOverride_withoutAValue_isAnExists() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentOverride(ID,
                new ExperimentOverrideRequest("ATTRIBUTE", "FORCE_INCLUDE", 0, null, "flag:vip", "exists", null, null))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/overrides"),
                "{\"target\":\"tag\",\"tagKey\":\"vip\",\"tagValue\":\" \",\"action\":\"FORCE_INCLUDE\",\"priority\":0}"))
                .andExpect(status().isCreated());
    }

    @Test
    void groupOverride_forcingAVariant_carriesTheVariant() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddExperimentOverride(ID,
                new ExperimentOverrideRequest("ATTRIBUTE", "FORCE_VARIANT", 1, null, "group", "in", "beta", OTHER))).thenReturn(201);

        mvc.perform(admin(post(BASE + "/overrides"),
                "{\"target\":\"group\",\"groupKey\":\"beta\",\"action\":\"FORCE_VARIANT\",\"priority\":1,\"variantId\":\"" + OTHER + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void override_withBlankRequiredFields_is400_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"tag\",\"tagKey\":\" \",\"action\":\"FORCE_INCLUDE\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"account\",\"action\":\"FORCE_INCLUDE\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"group\",\"action\":\"FORCE_INCLUDE\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"group\",\"groupKey\":\"b\",\"action\":\"FORCE_VARIANT\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"group\",\"groupKey\":\"b\",\"action\":\"NOPE\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/overrides"), "{\"target\":\"nope\",\"action\":\"FORCE_INCLUDE\"}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddExperimentOverride(any(), any());
    }

    @Test
    void moveAssignment_relaysTheVariant_andTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminMoveExperimentAssignment(ID, OTHER, OTHER)).thenReturn(204);
        when(apiService.adminMoveExperimentAssignment(ID, ID, OTHER)).thenReturn(404);

        mvc.perform(admin(post(BASE + "/assignments/" + OTHER + "/variant"), "{\"variantId\":\"" + OTHER + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/assignments/" + ID + "/variant"), "{\"variantId\":\"" + OTHER + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void moveAssignment_withoutAVariant_is400_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/assignments/" + OTHER + "/variant"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/assignments/" + OTHER + "/variant"), "{\"variantId\":null}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminMoveExperimentAssignment(any(), any(), any());
    }

    @Test
    void moveAssignment_refusesVisitorsAndMembers_evenWithoutAVariant() throws Exception {
        String url = BASE + "/assignments/" + OTHER + "/variant";
        mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminMoveExperimentAssignment(any(), any(), any());
    }

    @Test
    void unreachable_is502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminExperimentAction(ID, "pause")).thenReturn(502);

        mvc.perform(admin(post(BASE + "/pause"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void actions_refuseVisitorsAndMembers_evenOnLocalErrors() throws Exception {
        String[] urls = {BASE + "/activate", BASE + "/explode", BASE + "/weights", BASE + "/rules",
                BASE + "/rules/" + OTHER + "/delete", BASE + "/overrides", BASE + "/overrides/" + OTHER + "/delete"};
        for (String url : urls) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminExperimentAction(any(), any());
        verify(apiService, never()).adminExperimentWeights(any(), any());
        verify(apiService, never()).adminAddExperimentRule(any(), any());
        verify(apiService, never()).adminDeleteExperimentRule(any(), any());
        verify(apiService, never()).adminAddExperimentOverride(any(), any());
        verify(apiService, never()).adminDeleteExperimentOverride(any(), any());
    }
}
