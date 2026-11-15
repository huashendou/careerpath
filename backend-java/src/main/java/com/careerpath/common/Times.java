package com.careerpath.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 时间字段的统一换算。
 *
 * <p>库列是 {@code DATETIME}（无时区），契约要求响应里是带 {@code Z} 的 UTC：
 * 写入一律用 {@link #nowUtc()}（而不是库默认的 {@code CURRENT_TIMESTAMP}，后者取服务器本地时间，
 * 在 +08 的机器上会与读出的判断差 8 小时），读取一律按 UTC 解释。
 */
public final class Times {

    private Times() {
    }

    /** 当前 UTC 时间，精确到秒——DATETIME 不存小数秒，截断后再写可避免「写进去又读出来不相等」。 */
    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    public static Instant toInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime time = rs.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
