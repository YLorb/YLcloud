package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Runs the production converter against local fixtures, without connecting to application data. */
public class OfficePreviewSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        MinioclientUtil storage = new MinioclientUtil("http://127.0.0.1:9000", "fixture-user", "fixture-password", "fixtures") {
            @Override
            public InputStream getObjectStream(String fileUuid) throws Exception {
                return Files.newInputStream(root.resolve(fileUuid));
            }
        };
        OfficePreviewService converter = new OfficePreviewService(storage, "libreoffice");
        Files.createDirectories(root.resolve("pdf"));
        int count = 0;
        try (var inputs = Files.list(root)) {
            for (Path input : inputs.sorted().toList()) {
                String name = input.getFileName().toString();
                if (!name.startsWith("sample.") || !"office".equals(FilePreviewTypes.resolvePreviewType("", name))) continue;
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                Map<String, String> headers = new HashMap<>();
                ServletOutputStream stream = new ServletOutputStream() {
                    public boolean isReady() { return true; }
                    public void setWriteListener(WriteListener listener) { }
                    public void write(int value) { result.write(value); }
                    public void write(byte[] bytes, int offset, int length) { result.write(bytes, offset, length); }
                };
                HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                        OfficePreviewSmoke.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getOutputStream")) return stream;
                            if (method.getName().equals("setContentType")) headers.put("Content-Type", (String) arguments[0]);
                            if (method.getName().equals("setHeader")) headers.put((String) arguments[0], (String) arguments[1]);
                            return null;
                        });
                converter.writePdf(name, name, Files.size(input), response);
                byte[] pdf = result.toByteArray();
                if (!"application/pdf".equals(headers.get("Content-Type")) || pdf.length < 5
                        || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                    throw new IllegalStateException("Invalid PDF for " + name);
                }
                Files.write(root.resolve("pdf").resolve(name + ".pdf"), pdf);
                System.out.println("PASS " + name + ": " + pdf.length + " PDF bytes");
                count++;
            }
        }
        if (count < 3) throw new IllegalStateException("Expected at least three Office fixtures");
    }
}
