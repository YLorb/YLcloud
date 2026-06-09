package com.ylcloud.utils;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.VO.FileMergeReqVO;
import com.ylcloud.entity.File;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.DownloadObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MinIO 对象存储工具类。
 */
@Component
@Slf4j
public class MinioclientUtil {
    private final MinioClient minioClient;
    private final String DEFAULT_BUCKET = "localbucket1";

    /**
     * 创建 MinIO 客户端。
     *
     * @param endpoint MinIO 服务地址
     * @param accessKey MinIO 访问密钥
     * @param secretKey MinIO 私有密钥
     */
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
     * 列出所有存储桶。
     *
     * @throws Exception 查询失败时抛出
     */
    public void listBuckets() throws Exception {
        List<Bucket> list = minioClient.listBuckets();
        for(Bucket bucket : list) {
            log.info("bucket={}",bucket);
        }
    }

    /**
     * 创建存储桶。
     *
     * @param bucketName 存储桶名称
     * @throws Exception 创建失败时抛出
     */
    public void makeBucket(String bucketName) throws Exception {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 删除存储桶。
     *
     * @param bucketName 存储桶名称
     * @throws Exception 删除失败时抛出
     */
    public void removeBucket(String bucketName) throws Exception {
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 判断存储桶是否存在。
     *
     * @param bucketName 存储桶名称
     * @return 存储桶是否存在
     * @throws Exception 查询失败时抛出
     */
    public boolean bucketExists(String bucketName) throws Exception {
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }

    /**
     * 获取存储桶标签。
     */
    public void getBucketTags() {
        // TODO: 待实现
    }

    /**
     * 设置存储桶标签。
     */
    public void setBucketTags() {
        // TODO: 待实现
    }

    /**
     * 列出测试前缀下的对象。
     *
     * @throws Exception 查询失败时抛出
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
     * 上传完整文件对象。
     *
     * @param uploadFile 上传文件
     * @param UUID 对象名，当前系统使用文件 UUID
     * @throws Exception 上传失败时抛出
     */
    public void putObject(MultipartFile uploadFile,String UUID) throws Exception {
        ObjectWriteResponse objectWriteResponse = minioClient.putObject(PutObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(UUID)
                .stream(uploadFile.getInputStream(),-1,1024*1024*5)
                .build());
        log.info("上传完成：{}",objectWriteResponse.object());
    }

    /**
     * 删除文件对象。
     *
     * @param file 文件元数据
     * @throws Exception 删除失败时抛出
     */
    public void removeObject(File file) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(String.valueOf(file.getFileUuid()))
                .build());
    }

    /**
     * 将对象作为附件下载写入 HTTP 响应。
     *
     * @param fileDTO 文件信息
     * @param response HTTP 响应
     * @throws Exception 下载失败时抛出
     */
    public void getObject(FileDTO fileDTO,HttpServletResponse response) throws Exception {
        InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(fileDTO.getFileUuid())
                .build());

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition","attachment; filename=" + URLEncoder.encode(fileDTO.getName(), StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    /**
     * 获取对象输入流。
     *
     * @param fileUuid 文件 UUID，也是 MinIO 对象名
     * @return 对象输入流
     * @throws Exception 读取失败时抛出
     */
    public InputStream getObjectStream(String fileUuid) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(fileUuid)
                .build());
    }

    /**
     * 以内联方式输出对象，用于浏览器预览。
     *
     * @param fileUuid 文件 UUID，也是 MinIO 对象名
     * @param fileName 文件展示名
     * @param contentType HTTP 内容类型
     * @param response HTTP 响应
     * @throws Exception 输出失败时抛出
     */
    public void previewObject(String fileUuid, String fileName, String contentType, HttpServletResponse response) throws Exception {
        InputStream inputStream = getObjectStream(fileUuid);
        response.setContentType(contentType);
        response.setHeader("Content-Disposition","inline; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    /**
     * 下载对象到本地文件。
     *
     * @param fileDTO 文件信息
     * @param response HTTP 响应，当前方法未使用该参数
     * @throws Exception 下载失败时抛出
     */
    public void downloadObject(FileDTO fileDTO, HttpServletResponse response) throws Exception {
        minioClient.downloadObject(DownloadObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(fileDTO.getFileUuid())
                .filename(fileDTO.getName())
                .build());
    }

    /**
     * 创建空对象，用于新建空文件。
     *
     * @param objectName 对象名
     * @throws Exception 创建失败时抛出
     */
    public void putEmptyObject(String objectName) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(DEFAULT_BUCKET)
                .object(objectName)
                .stream(new ByteArrayInputStream(new byte[0]),0,-1)
                .contentType("application/octet-stream")
                .build());
    }

    /**
     * 上传单个文件分片到 MinIO 临时目录。
     *
     * @param fileUuid 上传任务 ID 或文件 UUID，用于组成分片临时目录
     * @param fileName 原始文件名
     * @param filepart 当前分片文件
     * @param chunkIndex 当前分片序号，从 0 开始
     * @param totalChunks 总分片数量
     * @return 分片在 MinIO 中的对象名
     * @throws IOException 分片上传失败时抛出
     */
    public String uploadFilePart(String fileUuid,String fileName,
                                 MultipartFile filepart,Integer chunkIndex,
                                 Integer totalChunks) throws IOException {
        try {
            String objectName = "chunks/" + fileUuid + "/" + chunkIndex;
            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(DEFAULT_BUCKET)
                    .object(objectName)
                    .stream(filepart.getInputStream(),filepart.getSize(),-1)
                    .contentType("application/octet-stream")
                    .build();
            minioClient.putObject(args);
            return objectName;
        } catch (Exception e) {
            throw new IOException("分片上传失败:" + e.getMessage(), e);
        }
    }

    /**
     * 使用 MinIO 服务端合并能力将分片合成为最终文件对象。
     *
     * @param reqVO 分片合并请求，包含最终文件 UUID 和分片对象名列表
     * @throws IOException 分片合并失败时抛出
     */
    public void mergeFileParts(FileMergeReqVO reqVO) throws IOException {
        try {
            List<ComposeSource> sources = reqVO.getPartNames().stream()
                    .map(name -> ComposeSource.builder()
                            .bucket(DEFAULT_BUCKET)
                            .object(name)
                            .build())
                    .toList();

            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(DEFAULT_BUCKET)
                    .object(reqVO.getFileUuid())
                    .sources(sources)
                    .build());

            removeFileParts(reqVO.getPartNames());
        } catch (Exception e) {
            throw new IOException("合并分片失败:" + e.getMessage(), e);
        }
    }

    /**
     * 删除 MinIO 中的临时分片对象。
     *
     * @param partNames 需要删除的分片对象名列表
     */
    public void removeFileParts(List<String> partNames) {
        partNames.forEach(partName -> {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(DEFAULT_BUCKET)
                        .object(partName)
                        .build());
            } catch (Exception e) {
                log.error("删除分片失败：{}",partName,e);
            }
        });
    }
}
