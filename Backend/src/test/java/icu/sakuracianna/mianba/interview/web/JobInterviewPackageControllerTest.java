package icu.sakuracianna.mianba.interview.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import icu.sakuracianna.mianba.identity.security.AuthenticatedUser;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPackageService;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewPackageView;
import icu.sakuracianna.mianba.interview.packageflow.JobInterviewStage;
import icu.sakuracianna.mianba.platform.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class JobInterviewPackageControllerTest {
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-15T08:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID packageId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            userId, "owner@example.com", "user", UUID.randomUUID(), 0);

    private JobInterviewPackageService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(JobInterviewPackageService.class);
        JobInterviewPackageController controller = new JobInterviewPackageController(service);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MethodValidationPostProcessor methodValidation = new MethodValidationPostProcessor();
        methodValidation.setValidator(validator);
        methodValidation.afterPropertiesSet();
        Object validatedController = methodValidation.postProcessAfterInitialization(
                controller, "jobInterviewPackageController");
        mvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setCustomArgumentResolvers(new FixedPrincipalResolver(principal))
                .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void createUsesOnlyPrincipalAndAllowedIdsAndReturnsCreatedSnakeCaseView() throws Exception {
        UUID firstSessionId = UUID.randomUUID();
        JobInterviewPackageView view = view(
                JobInterviewStage.TECHNICAL_FIRST, firstSessionId, "IN_PROGRESS");
        when(service.create(userId, packageId, firstSessionId, materialId, "package-key"))
                .thenReturn(view);

        mvc.perform(post("/api/interview-packages")
                        .header("Idempotency-Key", "package-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "package_id": "%s",
                                  "first_session_id": "%s",
                                  "material_id": "%s",
                                  "user_id": "%s",
                                  "charged_credit": 0,
                                  "admin_unlimited_usage": true,
                                  "plan": {"max_turns": 1},
                                  "material_snapshot": {"resume_text": "attacker"}
                                }
                                """.formatted(packageId, firstSessionId, materialId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/interview-packages/" + packageId))
                .andExpect(jsonPath("$.package_id").value(packageId.toString()))
                .andExpect(jsonPath("$.current_stage_code").value("TECHNICAL_FIRST"))
                .andExpect(jsonPath("$.charged_credit").value(3))
                .andExpect(jsonPath("$.admin_unlimited_usage").value(false))
                .andExpect(jsonPath("$.expires_at").value(EXPIRES_AT.toString()))
                .andExpect(jsonPath("$.stages[0].stage_code").value("TECHNICAL_FIRST"))
                .andExpect(jsonPath("$.stages[0].session_id").value(firstSessionId.toString()))
                .andExpect(jsonPath("$.stages[0].min_turns").value(8))
                .andExpect(jsonPath("$.stages[0].max_turns").value(12))
                .andExpect(jsonPath("$.stages[0].target_duration_minutes").value(50))
                .andExpect(jsonPath("$.stages[0].required_sections[0]").value("INTRODUCTION"))
                .andExpect(jsonPath("$.packageId").doesNotExist())
                .andExpect(jsonPath("$.stages[0].stageCode").doesNotExist());

        verify(service).create(userId, packageId, firstSessionId, materialId, "package-key");
    }

    @Test
    void activeReturnsOwnerPackageOrNoContent() throws Exception {
        when(service.active(userId))
                .thenReturn(Optional.of(view(
                        JobInterviewStage.TECHNICAL_FIRST, sessionId, "IN_PROGRESS")))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/interview-packages/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.package_id").value(packageId.toString()));
        mvc.perform(get("/api/interview-packages/active"))
                .andExpect(status().isNoContent());

        verify(service, org.mockito.Mockito.times(2)).active(userId);
    }

    @Test
    void getUsesPrincipalOwnerAndPathPackageId() throws Exception {
        when(service.get(userId, packageId)).thenReturn(view(
                JobInterviewStage.TECHNICAL_FIRST, sessionId, "IN_PROGRESS"));

        mvc.perform(get("/api/interview-packages/{packageId}", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.package_id").value(packageId.toString()));

        verify(service).get(userId, packageId);
    }

    @Test
    void startStageBindsStableEnumAndAllowedSessionIdFromPrincipalRequest() throws Exception {
        JobInterviewPackageView view = view(
                JobInterviewStage.TECHNICAL_SECOND, sessionId, "IN_PROGRESS");
        when(service.startStage(
                userId,
                packageId,
                JobInterviewStage.TECHNICAL_SECOND,
                sessionId,
                "stage-key"))
                .thenReturn(view);

        mvc.perform(post("/api/interview-packages/{packageId}/stages/{stageCode}/start",
                        packageId, "TECHNICAL_SECOND")
                        .header("Idempotency-Key", "stage-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "session_id": "%s",
                                  "user_id": "%s",
                                  "stage_code": "HR_FINAL",
                                  "charged_credit": 3
                                }
                                """.formatted(sessionId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/interview-packages/" + packageId))
                .andExpect(jsonPath("$.current_stage_code").value("TECHNICAL_SECOND"))
                .andExpect(jsonPath("$.stages[0].stage_code").value("TECHNICAL_SECOND"))
                .andExpect(jsonPath("$.stages[0].session_id").value(sessionId.toString()));

        verify(service).startStage(
                userId,
                packageId,
                JobInterviewStage.TECHNICAL_SECOND,
                sessionId,
                "stage-key");
    }

    @Test
    void invalidCreateRequestsUseExistingFourHundredAndValidationContracts() throws Exception {
        String validBody = """
                {"package_id":"%s","first_session_id":"%s","material_id":"%s"}
                """.formatted(packageId, sessionId, materialId);

        mvc.perform(post("/api/interview-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));
        mvc.perform(post("/api/interview-packages")
                        .header("Idempotency-Key", "package-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"package_id":"not-a-uuid","first_session_id":"%s","material_id":"%s"}
                                """.formatted(sessionId, materialId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed_json"));
        mvc.perform(post("/api/interview-packages")
                        .header("Idempotency-Key", "package-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"package_id":"%s","first_session_id":"%s"}
                                """.formatted(packageId, sessionId)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("validation_failed"));
        mvc.perform(post("/api/interview-packages")
                        .header("Idempotency-Key", "contains space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("validation_failed"));

        verifyNoInteractions(service);
    }

    @Test
    void invalidStageRequestsNeverReachService() throws Exception {
        mvc.perform(post("/api/interview-packages/{packageId}/stages/{stageCode}/start",
                        packageId, "SECOND_ROUND")
                        .header("Idempotency-Key", "stage-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));
        mvc.perform(post("/api/interview-packages/{packageId}/stages/{stageCode}/start",
                        "bad-package-id", "TECHNICAL_SECOND")
                        .header("Idempotency-Key", "stage-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));
        mvc.perform(post("/api/interview-packages/{packageId}/stages/{stageCode}/start",
                        packageId, "TECHNICAL_SECOND")
                        .header("Idempotency-Key", "stage-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("validation_failed"));
        mvc.perform(post("/api/interview-packages/{packageId}/stages/{stageCode}/start",
                        packageId, "TECHNICAL_SECOND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));

        verifyNoInteractions(service);
    }

    private JobInterviewPackageView view(
            JobInterviewStage stageCode, UUID boundSessionId, String stageStatus) {
        int minTurns = stageCode == JobInterviewStage.HR_FINAL ? 5 : 8;
        int maxTurns = stageCode == JobInterviewStage.HR_FINAL ? 8 : 12;
        int duration = stageCode == JobInterviewStage.HR_FINAL ? 25 : 50;
        return new JobInterviewPackageView(
                packageId,
                "ACTIVE",
                stageCode,
                3,
                false,
                EXPIRES_AT,
                List.of(new JobInterviewPackageView.Stage(
                        stageCode,
                        stageCode.sequence(),
                        stageStatus,
                        boundSessionId,
                        minTurns,
                        maxTurns,
                        duration,
                        List.of(stageCode == JobInterviewStage.HR_FINAL
                                ? "MOTIVATION"
                                : "INTRODUCTION"))));
    }

    private static final class FixedPrincipalResolver implements HandlerMethodArgumentResolver {
        private final AuthenticatedUser principal;

        private FixedPrincipalResolver(AuthenticatedUser principal) {
            this.principal = principal;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == AuthenticatedUser.class
                    && parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
            return principal;
        }
    }
}
