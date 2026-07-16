package icu.sakuracianna.mianba.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @Test
    void beanValidationUsesStableErrorEnvelope() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();

        mvc.perform(post("/validate")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req_validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("validation_failed"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.request_id").value("req_validation"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedRoutingAndProtocolErrorsRemainStableFourHundreds() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();

        mvc.perform(get("/items/not-a-uuid").header("Idempotency-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));
        mvc.perform(get("/items/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("request_validation_failed"));
        mvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value("unsupported_media_type"));
        mvc.perform(put("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.detail").value("method_not_allowed"));
    }

    @RestController
    static class ValidationController {
        @PostMapping("/validate")
        Payload validate(@Valid @RequestBody Payload payload) {
            return payload;
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable java.util.UUID id, @RequestHeader("Idempotency-Key") String key) {
            return id + key;
        }
    }

    record Payload(@NotBlank String name) {
    }
}
