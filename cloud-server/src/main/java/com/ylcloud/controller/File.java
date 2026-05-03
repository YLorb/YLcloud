package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.FileVO;
import com.ylcloud.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
@RequestMapping("/file")
public class File {

    private final FileService fileService;

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
}
