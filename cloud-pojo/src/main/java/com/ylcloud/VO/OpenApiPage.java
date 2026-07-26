package com.ylcloud.VO;

import java.util.List;

public record OpenApiPage<T>(List<T> items,int page,int pageSize,long total,boolean hasMore) {
    public static <T> OpenApiPage<T> of(List<T> values,int page,int pageSize) {
        int from = Math.min(values.size(),Math.max(0,(page-1)*pageSize));
        int to = Math.min(values.size(),from+pageSize);
        return new OpenApiPage<>(List.copyOf(values.subList(from,to)),page,pageSize,values.size(),to<values.size());
    }
}
