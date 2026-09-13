package com.ylcloud.service;

import com.ylcloud.config.RagProperties;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.service.admin.AdminFullTextSearchService;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminSearchWiringTest {
    @Test void usesExistingVectorServiceWithoutRequiringAnEmbeddingStoreBean() {
        new ApplicationContextRunner()
                .withBean(FileRagChunkMapper.class, () -> mock(FileRagChunkMapper.class))
                .withBean(SpaceMapper.class, () -> mock(SpaceMapper.class))
                .withBean(SpaceFileService.class, () -> mock(SpaceFileService.class))
                .withBean(RagProperties.class, RagProperties::new)
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(QdrantVectorStoreService.class, () -> mock(QdrantVectorStoreService.class))
                .withUserConfiguration(AdminFullTextSearchService.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AdminFullTextSearchService.class));
    }
}
