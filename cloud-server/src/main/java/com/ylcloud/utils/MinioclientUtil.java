package com.ylcloud.utils;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.VO.FileMergeReqVO;
import com.ylcloud.entity.File;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;

/**
 * MinIO 瀵硅薄瀛樺偍宸ュ叿绫汇€? */
@Component
@Slf4j
public class MinioclientUtil {
    private final MinioClient minioClient;
    private final String defaultBucket;

    /**
     * 鍒涘缓 MinIO 瀹㈡埛绔€?     *
     * @param endpoint MinIO 鏈嶅姟鍦板潃
     * @param accessKey MinIO 璁块棶瀵嗛挜
     * @param secretKey MinIO 绉佹湁瀵嗛挜
     */
    public MinioclientUtil(
            @Value("${ylcloud.minio.endpoint}") String endpoint,
            @Value("${ylcloud.minio.accessKey}") String accessKey,
            @Value("${ylcloud.minio.secretKey}") String secretKey,
            @Value("${ylcloud.minio.bucket-name:localbucket1}") String bucketName ) {
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey,secretKey)
                .build();
        this.defaultBucket = bucketName;
    }

    /**
     * 鍒楀嚭鎵€鏈夊瓨鍌ㄦ《銆?     *
     * @throws Exception 鏌ヨ澶辫触鏃舵姏鍑?     */
    public void listBuckets() throws Exception {
        List<Bucket> list = minioClient.listBuckets();
        for(Bucket bucket : list) {
            log.info("bucket={}",bucket);
        }
    }

    /**
     * 鍒涘缓瀛樺偍妗躲€?     *
     * @param bucketName 瀛樺偍妗跺悕绉?     * @throws Exception 鍒涘缓澶辫触鏃舵姏鍑?     */
    public void makeBucket(String bucketName) throws Exception {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 鍒犻櫎瀛樺偍妗躲€?     *
     * @param bucketName 瀛樺偍妗跺悕绉?     * @throws Exception 鍒犻櫎澶辫触鏃舵姏鍑?     */
    public void removeBucket(String bucketName) throws Exception {
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
    }

    /**
     * 鍒ゆ柇瀛樺偍妗舵槸鍚﹀瓨鍦ㄣ€?     *
     * @param bucketName 瀛樺偍妗跺悕绉?     * @return 瀛樺偍妗舵槸鍚﹀瓨鍦?     * @throws Exception 鏌ヨ澶辫触鏃舵姏鍑?     */
    public boolean bucketExists(String bucketName) throws Exception {
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }

    /**
     * Check whether the configured default bucket exists.
     */
    public boolean defaultBucketExists() throws Exception {
        return bucketExists(defaultBucket);
    }

    /**
     * Create the configured bucket when needed and enforce object versioning.
     */
    public void ensureDefaultBucketReady(boolean versioningEnabled) throws Exception {
        if(!defaultBucketExists()) {
            makeBucket(defaultBucket);
            log.info("Created MinIO bucket: {}",defaultBucket);
        }
        if(versioningEnabled && !isDefaultBucketVersioningEnabled()) {
            VersioningConfiguration configuration = new VersioningConfiguration(
                    VersioningConfiguration.Status.ENABLED,
                    null
            );
            minioClient.setBucketVersioning(SetBucketVersioningArgs.builder()
                    .bucket(defaultBucket)
                    .config(configuration)
                    .build());
            log.info("Enabled MinIO bucket versioning: {}",defaultBucket);
        }
    }

    /**
     * 鍒ゆ柇榛樿瀛樺偍妗舵槸鍚﹀凡寮€鍚璞＄増鏈帶鍒躲€?     *
     * @return true 琛ㄧず榛樿瀛樺偍妗跺凡寮€鍚増鏈帶鍒?     * @throws Exception 鏌ヨ澶辫触鏃舵姏鍑?     */
    public boolean isDefaultBucketVersioningEnabled() throws Exception {
        VersioningConfiguration configuration = minioClient.getBucketVersioning(
                GetBucketVersioningArgs.builder().bucket(defaultBucket).build()
        );
        return configuration != null && VersioningConfiguration.Status.ENABLED.equals(configuration.status());
    }

    /**
     * 鑾峰彇瀛樺偍妗舵爣绛俱€?     */
    public void getBucketTags() {
        // TODO: implement bucket tags when required.
    }

    /**
     * 璁剧疆瀛樺偍妗舵爣绛俱€?     */
    public void setBucketTags() {
        // TODO: implement bucket tags when required.
    }

    /**
     * 鍒楀嚭娴嬭瘯鍓嶇紑涓嬬殑瀵硅薄銆?     *
     * @throws Exception 鏌ヨ澶辫触鏃舵姏鍑?     */
    public void listObjects() throws Exception {
        Iterable<Result<Item>> iterable = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(defaultBucket)
                .prefix("abc")
                .recursive(true)
                .build());
        for (Result<Item> itemResult : iterable) {
            log.info("objectName={}", itemResult.get().objectName());
        }
    }

    /**
     * 涓婁紶瀹屾暣鏂囦欢瀵硅薄銆?     *
     * @param uploadFile 涓婁紶鏂囦欢
     * @param UUID 瀵硅薄鍚嶏紝褰撳墠绯荤粺浣跨敤鏂囦欢 UUID
     * @throws Exception 涓婁紶澶辫触鏃舵姏鍑?     */
    public void putObject(MultipartFile uploadFile,String UUID) throws Exception {
        ObjectWriteResponse objectWriteResponse = minioClient.putObject(PutObjectArgs.builder()
                .bucket(defaultBucket)
                .object(UUID)
                .stream(uploadFile.getInputStream(),-1,1024*1024*5)
                .build());
        log.info("涓婁紶瀹屾垚锛歿}",objectWriteResponse.object());
    }

    public void putObject(InputStream inputStream, long size, String contentType, String objectName) throws Exception {
        ObjectWriteResponse objectWriteResponse = minioClient.putObject(PutObjectArgs.builder()
                .bucket(defaultBucket)
                .object(objectName)
                .stream(inputStream,size,-1)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .build());
        log.info("涓婁紶瀹屾垚锛歿}",objectWriteResponse.object());
    }

    /**
     *
     * @param uploadFile
     * @param objectName
     * @return
     * @throws Exception
     */
    public String putObjectAndReturnVersionId(MultipartFile uploadFile, String objectName) throws Exception {
        ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                .bucket(defaultBucket)
                .object(objectName)
                .stream(uploadFile.getInputStream(),-1,1024 * 1024 * 5)
                .build());
        return response.versionId();
    }

    /**
     * Return the version id of the currently visible object version.
     */
    public String getCurrentObjectVersionId(String objectName) throws Exception {
        StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                .bucket(defaultBucket)
                .object(objectName)
                .build());
        return response.versionId();
    }

    /**
     * A composed object becomes visible atomically in MinIO.  A reserved object name plus
     * the expected size is therefore sufficient to recognize a completed retry.
     */
    public boolean objectMatchesSize(String objectName, long expectedSize) throws Exception {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(objectName)
                    .build());
            return response.size() == expectedSize;
        } catch (io.minio.errors.ErrorResponseException ex) {
            if(ex.errorResponse() != null && "NoSuchKey".equals(ex.errorResponse().code())) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 单次流式读取对象并计算合并验收所需的内容摘要，避免把大文件整体载入内存。
     */
    public ObjectDigests calculateObjectDigests(String objectName) throws Exception {
        try(InputStream inputStream = getObjectStream(objectName)) {
            return calculateDigests(inputStream);
        }
    }

    static ObjectDigests calculateDigests(InputStream inputStream) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while((read = inputStream.read(buffer)) >= 0) {
            if(read == 0) {
                continue;
            }
            md5.update(buffer,0,read);
            sha1.update(buffer,0,read);
            sha256.update(buffer,0,read);
        }
        HexFormat hex = HexFormat.of();
        return new ObjectDigests(
                hex.formatHex(md5.digest()),
                hex.formatHex(sha1.digest()),
                hex.formatHex(sha256.digest()));
    }

    public record ObjectDigests(String md5, String sha1, String sha256) {
    }

    public void removeObject(File file) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(defaultBucket)
                .object(String.valueOf(file.getFileUuid()))
                .build());
    }

    /**
     * 彻底删除对象的全部历史版本和删除标记。
     *
     * <p>只能用于业务层已经确认不存在任何文件引用的永久删除流程，不能用于回收站逻辑。</p>
     */
    public int removeObjectAllVersions(String objectName) throws Exception {
        if(objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("Object name must not be blank");
        }
        List<Item> versions = new ArrayList<>();
        Iterable<Result<Item>> iterable = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(defaultBucket)
                .prefix(objectName)
                .recursive(true)
                .includeVersions(true)
                .build());
        for(Result<Item> result : iterable) {
            Item item = result.get();
            if(objectName.equals(item.objectName())) {
                versions.add(item);
            }
        }
        for(Item item : versions) {
            RemoveObjectArgs.Builder builder = RemoveObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(objectName);
            if(item.versionId() != null && !item.versionId().isBlank()) {
                builder.versionId(item.versionId());
            }
            minioClient.removeObject(builder.build());
        }
        return versions.size();
    }

    /**
     * 灏嗗璞′綔涓洪檮浠朵笅杞藉啓鍏?HTTP 鍝嶅簲銆?     *
     * @param fileDTO 鏂囦欢淇℃伅
     * @param response HTTP 鍝嶅簲
     * @throws Exception 涓嬭浇澶辫触鏃舵姏鍑?     */
    public void getObject(FileDTO fileDTO,HttpServletResponse response) throws Exception {
        InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(defaultBucket)
                .object(fileDTO.getFileUuid())
                .build());

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition","attachment; filename=" + URLEncoder.encode(fileDTO.getName(), StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    /**
     * 鑾峰彇瀵硅薄杈撳叆娴併€?     *
     * @param fileUuid 鏂囦欢 UUID锛屼篃鏄?MinIO 瀵硅薄鍚?     * @return 瀵硅薄杈撳叆娴?     * @throws Exception 璇诲彇澶辫触鏃舵姏鍑?     */
    public InputStream getObjectStream(String fileUuid) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(defaultBucket)
                .object(fileUuid)
                .build());
    }

    public String getPresignedObjectUrl(String fileUuid, int expirySeconds) throws Exception {
        int expiry = expirySeconds <= 0 ? 300 : expirySeconds;
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(defaultBucket)
                .object(fileUuid)
                .expiry(expiry)
                .build());
    }

    /**
     * 浠ュ唴鑱旀柟寮忚緭鍑哄璞★紝鐢ㄤ簬娴忚鍣ㄩ瑙堛€?     *
     * @param fileUuid 鏂囦欢 UUID锛屼篃鏄?MinIO 瀵硅薄鍚?     * @param fileName 鏂囦欢灞曠ず鍚?     * @param contentType HTTP 鍐呭绫诲瀷
     * @param response HTTP 鍝嶅簲
     * @throws Exception 杈撳嚭澶辫触鏃舵姏鍑?     */
    public InputStream getObjectStream(String fileUuid, String versionId) throws Exception {
        GetObjectArgs.Builder builder = GetObjectArgs.builder()
                .bucket(defaultBucket)
                .object(fileUuid);
        if(versionId != null && !versionId.isBlank()) {
            builder.versionId(versionId);
        }
        return minioClient.getObject(builder.build());
    }

    public void previewObject(String fileUuid, String fileName, String contentType, HttpServletResponse response) throws Exception {
        InputStream inputStream = getObjectStream(fileUuid);
        response.setContentType(contentType);
        response.setHeader("Content-Disposition","inline; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    /**
     *
     * @param fileUuid
     * @param versionId
     * @param fileName
     * @param contentType
     * @param response
     * @throws Exception
     */
    public void previewObject(String fileUuid, String versionId, String fileName, String contentType, HttpServletResponse response) throws Exception {
        InputStream inputStream = getObjectStream(fileUuid,versionId);
        response.setContentType(contentType);
        response.setHeader("Content-Disposition","inline; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    public void getObject(String fileUuid, String versionId, String fileName, HttpServletResponse response) throws Exception {
        InputStream inputStream = getObjectStream(fileUuid,versionId);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition","attachment; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        IOUtils.copy(inputStream,response.getOutputStream());
        inputStream.close();
    }

    public String restoreObjectVersion(String fileUuid, String versionId) throws Exception {
        try (InputStream inputStream = getObjectStream(fileUuid,versionId)) {
            ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(fileUuid)
                    .stream(inputStream,-1,1024 * 1024 * 5)
                    .build());
            return response.versionId();
        }
    }

    public String copyObjectVersion(String sourceObjectName,
                                    String sourceVersionId,
                                    String targetObjectName) throws Exception {
        try (InputStream inputStream = getObjectStream(sourceObjectName,sourceVersionId)) {
            ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(targetObjectName)
                    .stream(inputStream,-1,1024 * 1024 * 5)
                    .build());
            return response.versionId();
        }
    }

    public void downloadObject(FileDTO fileDTO, HttpServletResponse response) throws Exception {
        throw new UnsupportedOperationException("Server-side local downloads are disabled; stream objects through HttpServletResponse instead.");
    }

    /**
     * 鍒涘缓绌哄璞★紝鐢ㄤ簬鏂板缓绌烘枃浠躲€?     *
     * @param objectName 瀵硅薄鍚?     * @throws Exception 鍒涘缓澶辫触鏃舵姏鍑?     */
    public void putEmptyObject(String objectName) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(defaultBucket)
                .object(objectName)
                .stream(new ByteArrayInputStream(new byte[0]),0,-1)
                .contentType("application/octet-stream")
                .build());
    }

    /**
     * 涓婁紶鍗曚釜鏂囦欢鍒嗙墖鍒?MinIO 涓存椂鐩綍銆?     *
     * @param fileUuid 涓婁紶浠诲姟 ID 鎴栨枃浠?UUID锛岀敤浜庣粍鎴愬垎鐗囦复鏃剁洰褰?     * @param fileName 鍘熷鏂囦欢鍚?     * @param filepart 褰撳墠鍒嗙墖鏂囦欢
     * @param chunkIndex 褰撳墠鍒嗙墖搴忓彿锛屼粠 0 寮€濮?     * @param totalChunks 鎬诲垎鐗囨暟閲?     * @return 鍒嗙墖鍦?MinIO 涓殑瀵硅薄鍚?     * @throws IOException 鍒嗙墖涓婁紶澶辫触鏃舵姏鍑?     */
    public String uploadFilePart(String fileUuid,String fileName,
                                 MultipartFile filepart,Integer chunkIndex,
                                 Integer totalChunks) throws IOException {
        try {
            String objectName = "chunks/" + fileUuid + "/" + chunkIndex;
            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(objectName)
                    .stream(filepart.getInputStream(),filepart.getSize(),-1)
                    .contentType("application/octet-stream")
                    .build();
            minioClient.putObject(args);
            return objectName;
        } catch (Exception e) {
            throw new IOException("鍒嗙墖涓婁紶澶辫触:" + e.getMessage(), e);
        }
    }

    /**
     * 浣跨敤 MinIO 鏈嶅姟绔悎骞惰兘鍔涘皢鍒嗙墖鍚堟垚涓烘渶缁堟枃浠跺璞°€?     *
     * @param reqVO 鍒嗙墖鍚堝苟璇锋眰锛屽寘鍚渶缁堟枃浠?UUID 鍜屽垎鐗囧璞″悕鍒楄〃
     * @throws IOException 鍒嗙墖鍚堝苟澶辫触鏃舵姏鍑?     */
    public void mergeFileParts(FileMergeReqVO reqVO) throws IOException {
        try {
            List<ComposeSource> sources = reqVO.getPartNames().stream()
                    .map(name -> ComposeSource.builder()
                            .bucket(defaultBucket)
                            .object(name)
                            .build())
                    .toList();

            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(reqVO.getFileUuid())
                    .sources(sources)
                    .build());

            // Temporary parts are deliberately retained until the database transaction
            // commits.  This makes a compose-success/database-failure retry possible.
        } catch (Exception e) {
            throw new IOException("鍚堝苟鍒嗙墖澶辫触:" + e.getMessage(), e);
        }
    }

    /**
     * 鍒犻櫎 MinIO 涓殑涓存椂鍒嗙墖瀵硅薄銆?     *
     * @param partNames 闇€瑕佸垹闄ょ殑鍒嗙墖瀵硅薄鍚嶅垪琛?     */
    public void removeFileParts(List<String> partNames) {
        partNames.forEach(partName -> {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(defaultBucket)
                        .object(partName)
                        .build());
            } catch (Exception e) {
                log.error("鍒犻櫎鍒嗙墖澶辫触锛歿}",partName,e);
            }
        });
    }

    /**
     * 删除临时分片；任一删除失败时向上抛出，使清理任务能够保留并重试。
     */
    public void removeFilePartsStrict(List<String> partNames) throws Exception {
        for(String partName : partNames) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(partName)
                    .build());
        }
    }

    /**
     * 按上传任务预分配的文件 UUID 清理全部临时分片，包含尚未来得及写入数据库的孤儿对象。
     */
    public int removeFilePartsByFileUuidStrict(String fileUuid) throws Exception {
        String prefix = "chunks/" + fileUuid + "/";
        List<String> objectNames = new ArrayList<>();
        Iterable<Result<Item>> items = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(defaultBucket)
                .prefix(prefix)
                .recursive(true)
                .build());
        for(Result<Item> result : items) {
            objectNames.add(result.get().objectName());
        }
        removeFilePartsStrict(objectNames);
        return objectNames.size();
    }

    /** 删除指定对象版本，用于数据库事务回滚后的精确补偿。 */
    public void removeObjectVersion(String objectName, String versionId) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(defaultBucket)
                .object(objectName)
                .versionId(versionId)
                .build());
    }
}
