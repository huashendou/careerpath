package com.careerpath.analysis;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.TraceIdFilter;
import com.careerpath.config.AnalysisProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Java → Python 的唯一出口。
 *
 * <p>契约要点（看板 c3.0 的「Java → Python 内部接口」）：
 * <ul>
 *   <li>路径前缀 {@code /internal}，必须带 {@code X-Internal-Token}；</li>
 *   <li>字段一律 camelCase，与对外契约同名，Java 侧不做命名转换；</li>
 *   <li>透传 {@code X-Trace-Id}，便于两进程对齐日志；</li>
 *   <li>分析服务不可用时映射为 {@link ErrorCode#ANALYSIS_UNAVAILABLE}（503），
 *       不要让 Python 的连接异常直接冒成 500——那是「依赖挂了」，不是「我们写错了」。</li>
 * </ul>
 */
@Component
public class AnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisClient.class);

    private final RestClient restClient;
    private final String token;

    public AnalysisClient(AnalysisProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.timeoutMs() > 0 ? properties.timeoutMs() : 1500;
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        this.token = properties.token();
    }

    /** 分析服务健康状态。失败不抛异常，由调用方决定是降级还是报错。 */
    public AnalysisHealth health() {
        long start = System.nanoTime();
        try {
            AnalysisHealth body = restClient.get()
                    .uri("/internal/health")
                    .header("X-Internal-Token", token)
                    .header(TraceIdFilter.HEADER, currentTraceId())
                    .retrieve()
                    .body(AnalysisHealth.class);
            long cost = (System.nanoTime() - start) / 1_000_000;
            if (body == null) {
                return AnalysisHealth.down("分析服务返回空响应");
            }
            return body.withLatencyMs(cost);
        } catch (Exception ex) {
            log.warn("分析服务健康检查失败：{}", ex.getMessage());
            return AnalysisHealth.down(ex.getClass().getSimpleName());
        }
    }

    /**
     * JD 解析（T3 接线，本阶段只固化通道与异常映射）。
     *
     * @param text      JD 原文
     * @param jobTitle  岗位名，仅用于两边日志对齐
     */
    public JdParseResult parseJd(String text, String jobTitle) {
        try {
            return restClient.post()
                    .uri("/internal/parse/jd")
                    .header("X-Internal-Token", token)
                    .header(TraceIdFilter.HEADER, currentTraceId())
                    .body(new JdParseRequest(text, jobTitle, null))
                    .retrieve()
                    .body(JdParseResult.class);
        } catch (Exception ex) {
            log.error("调用分析服务解析 JD 失败：{}", ex.getMessage());
            throw new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE);
        }
    }

    /**
     * 匹配算分（T4）。请求与响应字段全部与契约同名，Java 侧不做命名转换。
     *
     * <p>返回 {@code Map} 而不是逐字段 record：这份结果会被**原样存档**到
     * {@code skill_match_result.detail}，将来算法加字段时历史报告仍能完整回放；
     * 用 record 会丢掉未声明的字段，等于给未来挖坑。
     */
    public java.util.Map<String, Object> match(java.util.Map<String, Object> payload) {
        try {
            java.util.Map<String, Object> body = restClient.post()
                    .uri("/internal/match")
                    .header("X-Internal-Token", token)
                    .header(TraceIdFilter.HEADER, currentTraceId())
                    .body(payload)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {
                    });
            if (body == null || !body.containsKey("status") || !body.containsKey("counts")
                    || !body.containsKey("radar")) {
                log.error("分析服务匹配响应不符合契约，缺少 status/counts/radar");
                throw new ApiException(ErrorCode.ANALYSIS_FAILED);
            }
            return body;
        } catch (ApiException ex) {
            throw ex;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // 4xx 说明我们发的请求不对（契约漂移），5xx 说明对面算不动：两种都归「依赖不可用」，
            // 但日志要能区分，否则排查时只能看到一层 503。
            log.error("分析服务匹配失败：HTTP {} {}", ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE);
        } catch (Exception ex) {
            log.error("调用分析服务匹配失败：{}", ex.getMessage());
            throw new ApiException(ErrorCode.ANALYSIS_UNAVAILABLE);
        }
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null ? "-" : traceId;
    }

    // ---------------------------------------------------------------- 内部 DTO

    public record JdParseRequest(String text, String jobTitle, String source) {
    }

    /** 与分析服务 `/internal/health` 的响应逐字段对应。 */
    public record AnalysisHealth(String status, String service, String version, String time,
                                 Dictionary dictionary, String error, Long latencyMs) {

        public static AnalysisHealth down(String reason) {
            return new AnalysisHealth("down", "careerpath-analysis", null, null, null, reason, null);
        }

        public AnalysisHealth withLatencyMs(long millis) {
            return new AnalysisHealth(status, service, version, time, dictionary, error, millis);
        }

        public boolean up() {
            return "ok".equals(status);
        }
    }

    public record Dictionary(boolean loaded, String version, int categoryCount, int skillCount) {
    }

    /** `/internal/parse/jd` 的响应。T3 才会被控制器消费，此处先固化形状。 */
    public record JdParseResult(List<RequirementSuggestion> requirements, Conditions conditions,
                                int matchedCount, int requiredCount, int bonusCount,
                                String algorithmVersion, String dictionaryVersion, String disclaimer) {
    }

    public record RequirementSuggestion(String skillId, String skillName, String dim, String reqType,
                                        int weight, int targetLevel, String matchedAlias,
                                        int sourceStart, int sourceEnd, String sourceText,
                                        String evidenceHint, double confidence) {
    }

    public record Conditions(String education, String experience) {
    }
}
