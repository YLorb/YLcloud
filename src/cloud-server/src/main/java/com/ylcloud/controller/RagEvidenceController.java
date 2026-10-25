package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.rag.RagEvidenceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/space/{spaceId}/rag/evidence")
public class RagEvidenceController {
    private final RagEvidenceService evidence;
    public RagEvidenceController(RagEvidenceService evidence){this.evidence=evidence;}
    @GetMapping("/{id}")
    public Result<Map<String,Object>> detail(@PathVariable Long spaceId,@PathVariable String id){
        return Result.success(evidence.detail(spaceId,id,BaseContext.getCurrentId()));
    }
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long spaceId,@PathVariable String id,HttpServletResponse response) throws Exception {
        evidence.stream(spaceId,id,BaseContext.getCurrentId(),false,response);
    }
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long spaceId,@PathVariable String id,HttpServletResponse response) throws Exception {
        evidence.stream(spaceId,id,BaseContext.getCurrentId(),true,response);
    }
}
