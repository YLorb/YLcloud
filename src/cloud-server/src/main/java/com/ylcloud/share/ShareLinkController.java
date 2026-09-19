package com.ylcloud.share;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.utils.JwtUtil;
import io.jsonwebtoken.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static com.ylcloud.share.ShareModels.*;

@RestController
@RequiredArgsConstructor
public class ShareLinkController {
    private final ShareLinkService service;
    private final ShareRepository repo;
    private final AdminPermissionService admin;
    private final JwtUtil jwt;
    private final LoginMapper users;

    @PostMapping("/api/share-links")
    public Result<View> create(@RequestBody Create request){return Result.success(service.create(BaseContext.getCurrentId(),request));}
    @GetMapping("/api/share-links")
    public Result<Page<View>> mine(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return Result.success(service.list(BaseContext.getCurrentId(),page,size));
    }
    @PatchMapping("/api/share-links/{id}")
    public Result<View> update(@PathVariable long id,@RequestBody Conditions c){
        return Result.success(service.update(BaseContext.getCurrentId(),id,c));
    }
    @PostMapping("/api/share-links/{id}/revoke")
    public Result<Void> revoke(@PathVariable long id){service.revoke(BaseContext.getCurrentId(),id);return Result.success(null);}
    @GetMapping("/api/admin/share-links")
    public Result<Page<View>> all(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        admin.requireAdmin();return Result.success(service.list(null,page,size));
    }
    @GetMapping({"/api/admin/share-links/{id}/visits","/api/admin/share-link-visits"})
    public Result<Page<Map<String,Object>>> visits(@PathVariable(required=false) Long id,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        admin.requireAdmin();ShareLinkService.checkPage(page,size);return Result.success(repo.visits(id,page,size));
    }
    @PostMapping("/api/public/share-links/{code}/open")
    public Result<Open> open(@PathVariable String code,@RequestParam(defaultValue="false") boolean legacy,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return Result.success(service.open(legacy ? "legacy:"+code : code,optionalUser(request),request.getRemoteAddr()));
    }
    @PostMapping("/api/public/share-links/{code}/verify")
    public Result<Open> verify(@PathVariable String code,@RequestBody Verify body,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        try{return Result.success(service.verify(code,body));}catch(Expired ex){return Result.success(ShareLinkService.expired());}
    }
    @PostMapping("/api/public/share-links/{code}/download")
    public void download(@PathVariable String code,@RequestParam(defaultValue="") String credential,HttpServletResponse response) throws Exception {
        response.setHeader("Cache-Control","no-store");
        try(ShareLinkService.Download d=service.approve(code,credential)){
            String filename=ShareLinkService.safeName(d.name())+(d.directory()?".zip":"");
            response.setContentType(d.directory()?"application/zip":"application/octet-stream");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString());
            service.transfer(d,response.getOutputStream());
        }catch(Expired e){expiredHtml(response);}
        catch(Exception e){
            if(response.isCommitted())throw e;
            response.reset();response.setStatus(500);response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!doctype html><meta charset=\"UTF-8\"><h1>下载失败，请重试</h1>");
        }
    }
    public static void expiredHtml(HttpServletResponse response) throws java.io.IOException {
        response.setStatus(200);response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>文件已过期</title><body><main><h1>文件已过期</h1></main></body></html>");
    }
    private Long optionalUser(HttpServletRequest r){
        String token=r.getHeader("Authorization");if(token==null||token.isBlank())return null;
        Long id;
        try{id=jwt.parseToken(token.startsWith("Bearer ")?token.substring(7):token).get("userId",Long.class);}
        catch(JwtException|IllegalArgumentException e){return null;}
        if(id==null)return null;
        User u=users.getById(id);
        return u!=null&&Integer.valueOf(1).equals(u.getStatus())&&(u.getAccountStatus()==null||"ACTIVE".equals(u.getAccountStatus()))?id:null;
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> invalid(IllegalArgumentException e){
        return ResponseEntity.badRequest().body(Result.error(e.getMessage()));
    }
}
