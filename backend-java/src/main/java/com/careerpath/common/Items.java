package com.careerpath.common;

import java.util.List;

/**
 * 不分页的列表响应。
 *
 * <p>契约 c3.0：列表一律 {@code {"items":[...]}}，前端不用为每个接口写一套解析。
 * 用 record 而不是裸 {@code List}：以后要加 {@code total} 之类的元信息时不必改所有调用方。
 */
public record Items<T>(List<T> items) {

    public static <T> Items<T> of(List<T> items) {
        return new Items<>(items);
    }
}
