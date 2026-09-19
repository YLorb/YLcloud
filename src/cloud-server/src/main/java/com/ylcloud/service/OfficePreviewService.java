package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.utils.MinioclientUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OfficePreviewService {
    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private final MinioclientUtil minio;
    private final String executable;
    private final Semaphore slots = new Semaphore(2);

    public OfficePreviewService(MinioclientUtil minio,
            @Value("${ylcloud.preview.office.executable:libreoffice}") String executable) {
        this.minio = minio;
        this.executable = executable;
    }

    public void writePdf(String fileUuid, String fileName, Long size, HttpServletResponse response) {
        if (!"office".equals(FilePreviewTypes.resolvePreviewType("", fileName))) {
            throw new BaseException("当前文件类型不支持 Office 预览");
        }
        if (size != null && size > MAX_SIZE) {
            throw new BaseException("Office 文件超过 50 MB 预览上限");
        }
        if (!slots.tryAcquire()) throw new BaseException("预览服务繁忙，请稍后重试");
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("ylcloud-office-");
            String extension = fileName.substring(fileName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            Path source = directory.resolve("document" + extension);
            try (InputStream input = minio.getObjectStream(fileUuid); var output = Files.newOutputStream(source)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_SIZE) throw new BaseException("Office 文件超过 50 MB 预览上限");
                    output.write(buffer, 0, read);
                }
            }
            process = startConversion(directory, source);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new BaseException("Office 预览转换超时");
            }
            Path pdf = directory.resolve("document.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
                throw new BaseException("Office 预览转换失败");
            }
            if (Files.size(pdf) > 100L * 1024 * 1024) {
                throw new BaseException("转换后的 PDF 超过 100 MB 预览上限");
            }
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "inline; filename=preview.pdf");
            response.setHeader("Cache-Control", "private, no-store");
            try (InputStream input = Files.newInputStream(pdf)) {
                IOUtils.copy(input, response.getOutputStream());
            }
        } catch (BaseException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException("Office 预览已中断，请重试");
        } catch (Exception e) {
            log.warn("Office preview failed for file {}", fileUuid, e);
            throw new BaseException("Office 预览转换失败");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (IOException e) { log.warn("Could not remove Office preview temporary file: {}", path); }
                    });
                } catch (IOException e) { log.warn("Could not clean Office preview directory: {}", directory); }
            }
            slots.release();
        }
    }

    Process startConversion(Path directory, Path source) throws IOException {
        return new ProcessBuilder(executable, "-env:UserInstallation=" + directory.resolve("profile").toUri(),
                "--headless", "--norestore", "--convert-to", "pdf", "--outdir", directory.toString(), source.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }
}
