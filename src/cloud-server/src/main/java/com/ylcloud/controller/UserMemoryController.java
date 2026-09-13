package com.ylcloud.controller;

import com.ylcloud.DTO.UserMemorySettingUpdateDTO;
import com.ylcloud.DTO.UserMemoryUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.UserMemorySettingVO;
import com.ylcloud.VO.UserMemoryStatsVO;
import com.ylcloud.VO.UserMemoryVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.memory.UserMemoryManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/assistant/memories")
public class UserMemoryController {
    private final UserMemoryManagementService service;
    public UserMemoryController(UserMemoryManagementService service) { this.service=service; }

    @GetMapping public Result<List<UserMemoryVO>> list(@RequestParam(required=false) String type,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) Integer limit) {
        return Result.success(service.list(BaseContext.getCurrentId(),type,keyword,limit));
    }
    @GetMapping("/setting") public Result<UserMemorySettingVO> setting() { return Result.success(service.setting(BaseContext.getCurrentId())); }
    @PutMapping("/setting") public Result<UserMemorySettingVO> updateSetting(@RequestBody UserMemorySettingUpdateDTO dto) { return Result.success(service.updateSetting(BaseContext.getCurrentId(),dto)); }
    @GetMapping("/stats") public Result<UserMemoryStatsVO> stats() { return Result.success(service.stats(BaseContext.getCurrentId())); }
    @PutMapping("/{id}") public Result<UserMemoryVO> update(@PathVariable Long id,@RequestBody @Valid UserMemoryUpdateDTO dto) { return Result.success(service.update(BaseContext.getCurrentId(),id,dto)); }
    @PutMapping("/{id}/pin") public Result<UserMemoryVO> pin(@PathVariable Long id,@RequestParam boolean pinned) { return Result.success(service.pin(BaseContext.getCurrentId(),id,pinned)); }
    @DeleteMapping("/{id}") public Result<Boolean> forget(@PathVariable Long id) { service.forget(BaseContext.getCurrentId(),id); return Result.success(true); }
    @DeleteMapping public Result<Boolean> clear() { service.clear(BaseContext.getCurrentId()); return Result.success(true); }

    @GetMapping(value="/export",produces="text/csv")
    public ResponseEntity<byte[]> export() {
        StringBuilder csv=new StringBuilder("id,type,content,key,pinned,sourceSessionId,updatedAt\r\n");
        for(UserMemoryVO item:service.list(BaseContext.getCurrentId(),null,null,500)) {
            csv.append(item.getId()).append(',').append(cell(item.getMemoryType())).append(',').append(cell(item.getContent())).append(',')
                    .append(cell(item.getNormalizedKey())).append(',').append(Boolean.TRUE.equals(item.getPinned())).append(',')
                    .append(item.getSourceSessionId()).append(',').append(item.getUpdatetime()).append("\r\n");
        }
        byte[] body=("\uFEFF"+csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ylcloud-memories.csv")
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(body);
    }
    private String cell(Object value) { String text=value==null?"":String.valueOf(value); if(text.startsWith("=")||text.startsWith("+")||text.startsWith("-")||text.startsWith("@")) text="'"+text; return "\""+text.replace("\"","\"\"")+"\""; }
}
