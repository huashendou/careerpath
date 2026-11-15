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
