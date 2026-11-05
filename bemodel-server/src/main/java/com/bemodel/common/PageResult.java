package com.bemodel.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 服务端分页结果包装 */
@Data
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;
    private int pageNum;
    private int pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageResult<>(list, total, pageNum, pageSize);
    }

    /** 页参数归一化：pageNum 从 1 开始，pageSize 限制 [1, 200] */
    public static int pageNum(Integer p) {
        return p == null || p < 1 ? 1 : p;
    }

    public static int pageSize(Integer s, int def) {
        if (s == null || s < 1) {
            return def;
        }
        return Math.min(s, 200);
    }
}
