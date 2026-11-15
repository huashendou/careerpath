package com.careerpath.health;

import com.careerpath.analysis.AnalysisClient;
import com.careerpath.skill.mapper.SkillCategoryMapper;
import com.careerpath.skill.mapper.SkillMapper;
import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：{@code GET /api/health}（免鉴权）。
 *
 * <p>一个接口回答三件事：进程活着没有、数据库通不通、分析服务通不通。
 * 三进程联调时，前端和启动脚本只认这一个地址就能判断「是不是都起来了」。
 *
 * <p>状态码约定：数据库不可用 → 503（业务整体不可用）；
 * 仅分析服务不可用 → 200 + {@code status=degraded}（浏览、录入仍可用，只是解析与算分降级）。
 * 这样才不会因为 Python 挂掉就把整个平台判死。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final String SERVICE_NAME = "careerpath-api";
    private static final String SERVICE_VERSION = "0.1.0";

    private final DataSource dataSource;
    private final SkillMapper skillMapper;
    private final SkillCategoryMapper categoryMapper;
    private final AnalysisClient analysisClient;

    public HealthController(DataSource dataSource, SkillMapper skillMapper,
                            SkillCategoryMapper categoryMapper, AnalysisClient analysisClient) {
        this.dataSource = dataSource;
        this.skillMapper = skillMapper;
        this.categoryMapper = categoryMapper;
        this.analysisClient = analysisClient;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        DatabaseStatus database = checkDatabase();
        AnalysisClient.AnalysisHealth analysis = analysisClient.health();

        boolean dbUp = "up".equals(database.status());
        boolean analysisUp = analysis != null && analysis.up();
        String status = dbUp && analysisUp ? "ok" : "degraded";

        HealthResponse body = new HealthResponse(
                status,
                SERVICE_NAME,
                SERVICE_VERSION,
                Instant.now().toString(),
                database,
                analysis);

        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private DatabaseStatus checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            long skills = skillMapper.selectCount(null);
            long categories = categoryMapper.selectCount(null);
            return new DatabaseStatus("up", catalog, skills, categories, null);
        } catch (Exception ex) {
            return new DatabaseStatus("down", null, 0, 0, ex.getClass().getSimpleName());
        }
    }

    public record HealthResponse(String status, String service, String version, String time,
                                 DatabaseStatus database, AnalysisClient.AnalysisHealth analysis) {
    }

    public record DatabaseStatus(String status, String name, long skillCount, long categoryCount,
                                 String error) {
    }
}
