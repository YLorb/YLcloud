package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.FileVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.lang.annotation.Target;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/file")
//@RequestMapping("/api/files)
public class File {

    @Autowired
    private FileService fileService;

    public File(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "parentId",defaultValue = "0") Long parentId) {
        return Result.success(fileService.upload(file,parentId));
    }

    @GetMapping("/download/{fileUuid}")
    public void download(@PathVariable String fileUuid, HttpServletResponse response) {
        fileService.downloadFile(fileUuid,response);
    }

    @PutMapping("/rename/{fileUuid}")
    public Result<FileVO> rename(@PathVariable String fileUuid, @RequestParam String new_name) {
        return Result.success(fileService.renameFile(fileUuid,new_name));
    }

    @DeleteMapping("/{fileUuid}")
    public Result<Boolean> delete(@PathVariable String fileUuid) {
        return Result.success(fileService.deleteFiles(fileUuid));
    }

    @PostMapping("/{is_Dir}")
    public Result<FileVO> makefile(@PathVariable int is_Dir,
                                   @RequestParam("ParentId") Long parentId,
                                   @RequestParam("Name") String name,
                                   @RequestParam("type") String type) {
        return Result.success(fileService.makefile(is_Dir,parentId,name,type));
    }

    @GetMapping("/list")
    public Result<List<FileVO>> listFiles(@RequestParam(value = "parentId",defaultValue = "0") Long parentId) {
        Long userId = BaseContext.getCurrentId();
        List<FileVO> files = fileService.listFiles(parentId,userId);
        return Result.success(files);
    }

    @GetMapping("/bucket")
    public Result<Boolean> bucket_exists() {
        return Result.success(fileService.bucketExists());
    }

    @PutMapping("/move")
    public Result<Boolean> movefiles(@RequestParam Long sourceplace, @RequestParam Long targetplace) {
        return Result.success(fileService.movefiles(sourceplace,targetplace));
    }
}
