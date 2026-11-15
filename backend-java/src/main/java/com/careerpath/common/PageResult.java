package com.careerpath.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import java.util.function.Function;

/**
 * 分页响应。
 *
 * <p>列表接口一律返回它，字段名与 c3.0 契约一致：前端不用为每个接口写一套分页解析。
 * 不分页的历史接口（如技能词典）仍按 v1 习惯直接返回 {@code {total, items}} 形状。
 *
 * @param items 当前页数据
 * @param total 总条数
 * @param page  页码，从 1 开始
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0, page, size);
    }
}
