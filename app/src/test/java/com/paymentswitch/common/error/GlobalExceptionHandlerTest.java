package com.paymentswitch.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentswitch.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void apiExceptionKeepsItsStatusAndCode() {
        MvcTestResult result = mvc.get().uri("/probe/conflict").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DUPLICATE_REQUEST");
        assertThat(result).bodyJson().extractingPath("$.traceId").isNotNull();
    }

    @Test
    void invalidBodyListsFieldProblems() {
        MvcTestResult result = mvc.post().uri("/probe/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.details[0].field").isEqualTo("name");
    }

    @Test
    void malformedJsonIsBadRequest() {
        MvcTestResult result = mvc.post().uri("/probe/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void unexpectedExceptionDoesNotLeakDetails() {
        MvcTestResult result = mvc.get().uri("/probe/boom").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyText().doesNotContain("db-password");
    }

    @RestController
    static class ProbeController {

        record Body(@NotBlank String name) {
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "Duplicate request");
        }

        @PostMapping("/probe/validated")
        void validated(@Valid @RequestBody Body body) {
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("connection failed for db-password=secret");
        }
    }
}
