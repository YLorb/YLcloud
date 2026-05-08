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

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/file")
public class File {

    @Autowired
    private FileService fileService;

    public File(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileService.upload(file));
    }

    @GetMapping("/download/{fileUuid}")
    public void download(@PathVariable String fileUuid, HttpServletResponse response) {
        fileService.downloadFile(fileUuid,response);
    }

    @PutMapping("/rename/{fileUuid}")
    public Result rename(@PathVariable String fileUuid, @RequestParam String new_name) {
        return Result.success(fileService.renameFile(fileUuid,new_name));
    }



    /*@PutMapping("/delete/{fileUuid}")
    public Result<Boolean> delete(@PathVariable String fileUuid) {
        return Result.success(fileService.deleteFile(fileUuid));
    }*/

    //@PostMapping("makefile/{is_Dir}")
    /*
    public Result<FileVO> makefile(@PathVariable Integer is_Dir) {
        return Result.success(fileService.makefile(is_Dir));
    }
     */

    @GetMapping("/list")
    public Result<List<FileVO>> listFiles() {
        Long userId = BaseContext.getCurrentId();
        List<FileVO> files = fileService.listFiles(userId);
        return Result.success(files);
    }

    @GetMapping("/bucket")
    public Result<Boolean> bucket_exists() {
        return Result.success(fileService.bucketExists());
    }
}
