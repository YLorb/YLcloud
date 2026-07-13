package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MinioStorageInitializerTest {
    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments(new String[0]);

    @Test
    void initializesConfiguredBucket() throws Exception {
        MinioclientUtil minioClient = mock(MinioclientUtil.class);

        new MinioStorageInitializer(minioClient,true,true).run(NO_ARGS);

        verify(minioClient).ensureDefaultBucketReady(true);
    }

    @Test
    void skipsWhenDisabled() throws Exception {
        MinioclientUtil minioClient = mock(MinioclientUtil.class);

        new MinioStorageInitializer(minioClient,false,true).run(NO_ARGS);

        verify(minioClient,never()).ensureDefaultBucketReady(true);
    }
}
