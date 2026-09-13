package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.mapper.AdminMetricsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminMetricsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AdminMetricsMapper mapper;
    public record Day(String date, long newFiles, long newBlobs, long newUsers, long newSpaces) {}
    public record Metrics(String timezone, boolean tokenStatisticsAvailable, List<Day> days) {}

    public Metrics daily(int days) { return daily(days, LocalDate.now(ZONE)); }
    Metrics daily(int days, LocalDate today) {
        if (days < 1 || days > 90) throw new BaseException("统计范围必须在 1 到 90 天之间");
        LocalDate first = today.minusDays(days - 1L);
        long start = first.atStartOfDay(ZONE).toEpochSecond();
        long end = today.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        Map<Long,Long> users = counts(mapper.users(start,end));
        Map<Long,Long> blobs = counts(mapper.blobs(start,end));
        Map<Long,Long> spaces = counts(mapper.spaces(start,end));
        Map<Long,Long> references = counts(mapper.references(start,end));
        List<Day> result = new ArrayList<>();
        for (int i=0; i<days; i++) {
            LocalDate date = first.plusDays(i); long key = date.toEpochDay();
            result.add(new Day(date.toString(), references.getOrDefault(key,0L), blobs.getOrDefault(key,0L), users.getOrDefault(key,0L), spaces.getOrDefault(key,0L)));
        }
        return new Metrics(ZONE.getId(), false, result);
    }
    private Map<Long,Long> counts(List<Map<String,Object>> rows) {
        Map<Long,Long> result = new HashMap<>();
        for (Map<String,Object> row : rows) result.put(((Number)row.get("day")).longValue(), ((Number)row.get("total")).longValue());
        return result;
    }
}
