package com.ylcloud.utils;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.entity.File;
import io.minio.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 代码参考至：https://www.cnblogs.com/wuyongyin/p/19069599
 */
@Component
@Slf4j
public class MinioclientUtil {
    private MinioClient minioClient;
    private final String DEFAULT_BUCKET = "localbucket1";

    public MinioclientUtil(
            @Value("${ylcloud.minio.endpoint}") String endpoint,
            @Value("${ylcloud.minio.accessKey}") String accessKey,
            @Value("${ylcloud.minio.secretKey}") String secretKey ) {
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey,secretKey)
                .build();
    }

    /**
     * 列出桶信息
     */
    public void listBuckets() throws Exception {
        List<Bucket> list = minioClient.listBuckets();
        for(Bucket bucket : list) {
            log.info("bucket={}",bucket);
        }
    }

    /**
     * 新建桶
     */
    public void makeBucket(String bucketName) throws Exception {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 删除桶
     */
    public void removeBucket(String bucketName) throws Exception {
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 判断桶是否存在
     */
    public boolean bucketExists(String bucketName) throws Exception {
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }

    /**
     * 获取桶标签
     */
    public void getBucketTags() throws Exception {
        // TODO:待实现
    }

    /**
     * 设置桶标签
     */
    public void setBucketTags() throws Exception {
        // TODO:待实现
    }

    /**
     * 列出桶的对象信息
     */
    public void listObjects() throws Exception {
        Iterable<Result<Item>> iterable = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket("test-bucket")
                .prefix("abc")
                .recursive(true)
                .build());
        for (Result<Item> itemResult : iterable) {
            log.info("objectName={}", itemResult.get().objectName());
        }
    }

    /**
     * 上传（将输入流作为对象）
     */
    public void putObject(MultipartFile uploadFile,String UUID) throws Exception {
        ObjectWriteResponse objectWriteResponse = minioClient.putObject(PutObjectArgs.builder()
                        .bucket(DEFAULT_BUCKET)
                        .object(UUID)
                        .stream(uploadFile.getInputStream(),-1,1024*1024*5).build());
        log.info("上传完成：{}",objectWriteResponse.object());
    }

    /**
     * 删除桶中对象
     */
    public void removeObject(File file) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(String.valueOf(file.getFileUuid()))
                .build());
    }

    /**
     * 批量删除桶中对象
     */

    /**
     * 获取桶中的对象
     */
    public void getObject(FileDTO fileDTO,HttpServletResponse response) throws Exception {
        InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(fileDTO.getFileUuid())
                .build());

        // 设置响应类型
        response.setContentType("application/octet-stream");
        // 设置文件名
        response.setHeader("Content-Disposition","attachment; filename=" + URLEncoder.encode(fileDTO.getName(), StandardCharsets.UTF_8));
        //3. 设置下载时显示的文件名 & 文件编码
        // 将 MinIO 的文件流写进了 HTTP 响应体。
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    /**
     * 下载桶内对象到本地
     */

    public void downloadObject(FileDTO fileDTO, HttpServletResponse response) throws Exception {
        minioClient.downloadObject(DownloadObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(fileDTO.getFileUuid())
                .filename(fileDTO.getName())
                .build());

    }

    /**
     * 创建空对象
     */
    //TODO:需要理解这里的代码
    public void putEmptyObject(String objectName) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(objectName)
                .stream(new ByteArrayInputStream(new byte[0]),0,-1)
                .contentType("application/octet-stream")
                .build());
    }

    /**
     * 拷贝桶中对象
     */

    /**
     * 获取对象标签
     */

    /**
     * 设置对象标签
     */

    /**
     * 删除对象标签
     */
}
