package icu.sakuracianna.mianba.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import icu.sakuracianna.mianba.interview.material.MaterialParserClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    @Test
    void returnsParserReadyWhenEveryRequiredDependencyIsReady() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenReturn(true);
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.database_ready").value(true))
                .andExpect(jsonPath("$.redis_ready").value(true))
                .andExpect(jsonPath("$.rabbit_ready").value(true))
                .andExpect(jsonPath("$.worker_ready").value(true))
                .andExpect(jsonPath("$.parser_ready").value(true));
    }

    @Test
    void returnsUnavailableWhenMaterialParserIsNotReady() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenReturn(false);
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("material_parser_not_ready"));
    }

    @Test
    void hidesMaterialParserFailureDetailsFromReadinessResponse() throws Exception {
        MaterialParserClient parser = mock(MaterialParserClient.class);
        when(parser.isReady()).thenThrow(new IllegalStateException("internal-token-must-not-leak"));
        MockMvc mvc = mvc(parser);

        mvc.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("material_parser_not_ready"))
                .andExpect(jsonPath("$.message").value("材料解析服务尚未就绪"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MockMvc mvc(MaterialParserClient parser) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject(contains("runtime_heartbeats"), eq(Boolean.class))).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(false).when(redis).hasKey(any(String.class));
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doReturn(true).when(rabbit).execute(any(ChannelCallback.class));
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        HealthController controller = new HealthController(jdbc, redis, rabbit, clock, parser);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
