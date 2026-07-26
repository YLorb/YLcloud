package com.ylcloud.VO;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class AsyncTaskPageVO {
    private List<AsyncTaskVO> records;
    private long total;
    private int page;
    private int pageSize;
}
