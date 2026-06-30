# Mapper 表清单

本文档由 `cloud-server/src/main/java/com/ylcloud/mapper` 下的 Mapper SQL 注解整理，并对照 `cloud-server/src/main/resources/db` 下的建表与迁移 SQL 补充字段信息。

## 统计

- Mapper 文件数: 25
- Mapper 引用表数: 25
- 有 `create table` 定义的引用表: 22
- 仅找到迁移/旧设计文档、未找到完整建表 SQL 的引用表: 3

## Mapper 与表对应关系

| Mapper | 表名 |
| --- | --- |
| `ChunkUploadMapper.java` | `upload_chunk` |
| `FileInfoMapper.java` | `file_info`, `user_file` |
| `FileRagChunkMapper.java` | `file_rag_chunk`, `space_knowledge_document_profile`, `space_knowledge_question`, `space_rag_chunk_ref`, `space_rag_document` |
| `FileRagParseResultMapper.java` | `file_rag_parse_result` |
| `FileShareMapper.java` | `file_share` |
| `FileVersionMapper.java` | `file_version` |
| `LoginMapper.java` | `users` |
| `MultifileMapper.java` | `upload_task` |
| `SignMapper.java` | `users` |
| `SiteSettingMapper.java` | `site_setting` |
| `SpaceFileMapper.java` | `space_file` |
| `SpaceKnowledgeAuditLogMapper.java` | `space_knowledge_audit_log` |
| `SpaceKnowledgeDocumentProfileMapper.java` | `space_knowledge_document_profile` |
| `SpaceKnowledgePipelineEventMapper.java` | `space_knowledge_pipeline_event` |
| `SpaceKnowledgePipelineTaskMapper.java` | `space_knowledge_pipeline_task` |
| `SpaceKnowledgeProfileVersionMapper.java` | `space_knowledge_profile_version` |
| `SpaceKnowledgeQuestionMapper.java` | `space_knowledge_question` |
| `SpaceMapper.java` | `space_member`, `spaces` |
| `SpaceMemberMapper.java` | `space_member` |
| `SpaceRagChunkRefMapper.java` | `space_rag_chunk_ref` |
| `SpaceRagConfigLogMapper.java` | `space_rag_config_log` |
| `SpaceRagDocumentMapper.java` | `space_file`, `space_rag_chunk_ref`, `space_rag_document` |
| `SpaceRagMapper.java` | `space_rag_config` |
| `SpaceRagQueryLogMapper.java` | `space_rag_query_log` |
| `SpaceRagTaskMapper.java` | `space_rag_task` |

## 表与 Mapper 对应关系

| 表名 | 表注释 | Mapper | 结构来源 |
| --- | --- | --- | --- |
| `file_info` | - | `FileInfoMapper.java` | `cloud-server/src/main/resources/db/migration/V3__multipart_sha1_upload_id.sql`（无完整 create table） |
| `file_rag_chunk` | - | `FileRagChunkMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `file_rag_parse_result` | - | `FileRagParseResultMapper.java` | `cloud-server/src/main/resources/db/migration/V4__rag_structured_parse_result.sql` |
| `file_share` | - | `FileShareMapper.java` | `cloud-server/src/main/resources/db/permission-share.sql` |
| `file_version` | - | `FileVersionMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `site_setting` | - | `SiteSettingMapper.java` | `cloud-server/src/main/resources/db/migration/V5__site_settings.sql` |
| `space_file` | - | `SpaceFileMapper.java`, `SpaceRagDocumentMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_knowledge_audit_log` | - | `SpaceKnowledgeAuditLogMapper.java` | `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql` |
| `space_knowledge_document_profile` | - | `FileRagChunkMapper.java`, `SpaceKnowledgeDocumentProfileMapper.java` | `cloud-server/src/main/resources/db/migration/V10__space_knowledge_pipeline_incremental.sql`, `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql`, `cloud-server/src/main/resources/db/migration/V8__space_knowledge_pipeline_observability.sql`, `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql` |
| `space_knowledge_pipeline_event` | - | `SpaceKnowledgePipelineEventMapper.java` | `cloud-server/src/main/resources/db/migration/V8__space_knowledge_pipeline_observability.sql` |
| `space_knowledge_pipeline_task` | - | `SpaceKnowledgePipelineTaskMapper.java` | `cloud-server/src/main/resources/db/migration/V10__space_knowledge_pipeline_incremental.sql`, `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql` |
| `space_knowledge_profile_version` | - | `SpaceKnowledgeProfileVersionMapper.java` | `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql` |
| `space_knowledge_question` | - | `FileRagChunkMapper.java`, `SpaceKnowledgeQuestionMapper.java` | `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql` |
| `space_member` | - | `SpaceMapper.java`, `SpaceMemberMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_chunk_ref` | - | `FileRagChunkMapper.java`, `SpaceRagChunkRefMapper.java`, `SpaceRagDocumentMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_config` | - | `SpaceRagMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_config_log` | - | `SpaceRagConfigLogMapper.java` | `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_document` | - | `FileRagChunkMapper.java`, `SpaceRagDocumentMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_query_log` | - | `SpaceRagQueryLogMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `space_rag_task` | - | `SpaceRagTaskMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V2__rag_task_progress.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `spaces` | - | `SpaceMapper.java` | `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql` |
| `upload_chunk` | - | `ChunkUploadMapper.java` | `cloud-server/src/main/resources/db/multipart-upload.sql` |
| `upload_task` | - | `MultifileMapper.java` | `cloud-server/src/main/resources/db/migration/V3__multipart_sha1_upload_id.sql`, `cloud-server/src/main/resources/db/multipart-upload.sql` |
| `user_file` | - | `FileInfoMapper.java` | `cloud database.md`（无完整 create table） |
| `users` | - | `LoginMapper.java`, `SignMapper.java` | `cloud-server/src/main/resources/db/permission-share.sql`（无完整 create table） |

## 表结构

### `file_info`

- Mapper: `FileInfoMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V3__multipart_sha1_upload_id.sql`
- 完整性：未找到完整 `create table`，以下字段来自迁移语句或旧设计文档。

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `sha1` | `varchar(40) after md5', 'select 1' ) )` | - |

迁移补充字段：
- `add column sha1 varchar(40) after md5', 'select 1' ) )`

### `file_rag_chunk`

- Mapper: `FileRagChunkMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `file_uuid` | `varchar(64` | - |

### `file_rag_parse_result`

- Mapper: `FileRagParseResultMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V4__rag_structured_parse_result.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `file_uuid` | `varchar(64` | - |

### `file_share`

- Mapper: `FileShareMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/permission-share.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `share_code` | `varchar(64` | - |

### `file_version`

- Mapper: `FileVersionMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `file_uuid` | `varchar(64` | - |

### `site_setting`

- Mapper: `SiteSettingMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V5__site_settings.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `setting_key` | `varchar(100` | - |

### `space_file`

- Mapper: `SpaceFileMapper.java`, `SpaceRagDocumentMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `file_uuid` | `varchar(64` | - |

### `space_knowledge_audit_log`

- Mapper: `SpaceKnowledgeAuditLogMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `operator_id` | `bigint` | - |
| `action` | `varchar(60` | - |

### `space_knowledge_document_profile`

- Mapper: `FileRagChunkMapper.java`, `SpaceKnowledgeDocumentProfileMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V10__space_knowledge_pipeline_incremental.sql`, `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql`, `cloud-server/src/main/resources/db/migration/V8__space_knowledge_pipeline_observability.sql`, `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `source_file_hash` | `varchar(128) null, add column source_parser_version varchar(80) null, add column profile_schema_version varchar(80) null` | - |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint not null` | - |
| `space_file_id` | `bigint not null` | - |
| `title` | `varchar(255` | - |
| `raw_llm_output` | `longtext null, add column normalized_profile_json longtext null, add column quality_detail_json longtext null, add column quality_issue_json longtext null, add column score_before_repair decimal(5,2) default 0.00, add column score_after_repair decimal(5,2) default 0.00, add column review_status varchar(30) not null default 'NOT_REQUIRED', add column review_reason varchar(1000) null, add column source_chunk_ids longtext null, add column source_chunk_count int not null default 0, add column source_character_count int not null default 0, add column schema_valid tinyint(1) not null default 0, add column repair_attempt int not null default 0, add column repair_reason varchar(1000) null, add column profile_version int not null default 1` | - |
| `current_version_id` | `bigint null, add column latest_version_id bigint null` | - |

迁移补充字段：
- `add column source_file_hash varchar(128) null, add column source_parser_version varchar(80) null, add column profile_schema_version varchar(80) null`
- `add column raw_llm_output longtext null, add column normalized_profile_json longtext null, add column quality_detail_json longtext null, add column quality_issue_json longtext null, add column score_before_repair decimal(5,2) default 0.00, add column score_after_repair decimal(5,2) default 0.00, add column review_status varchar(30) not null default 'NOT_REQUIRED', add column review_reason varchar(1000) null, add column source_chunk_ids longtext null, add column source_chunk_count int not null default 0, add column source_character_count int not null default 0, add column schema_valid tinyint(1) not null default 0, add column repair_attempt int not null default 0, add column repair_reason varchar(1000) null, add column profile_version int not null default 1`
- `add column current_version_id bigint null, add column latest_version_id bigint null`

### `space_knowledge_pipeline_event`

- Mapper: `SpaceKnowledgePipelineEventMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V8__space_knowledge_pipeline_observability.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `task_id` | `bigint not null` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint` | - |
| `stage` | `varchar(60` | - |

### `space_knowledge_pipeline_task`

- Mapper: `SpaceKnowledgePipelineTaskMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V10__space_knowledge_pipeline_incremental.sql`, `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `force_rebuild` | `tinyint(1) not null default 0, add column terminal_stage varchar(60) null, add column terminal_reason varchar(120) null, add column incremental_action varchar(60) null, add column incremental_detail varchar(1000) null` | - |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint` | - |
| `task_type` | `varchar(30` | - |

迁移补充字段：
- `add column force_rebuild tinyint(1) not null default 0, add column terminal_stage varchar(60) null, add column terminal_reason varchar(120) null, add column incremental_action varchar(60) null, add column incremental_detail varchar(1000) null`

### `space_knowledge_profile_version`

- Mapper: `SpaceKnowledgeProfileVersionMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V9__space_knowledge_profile_asset_management.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `profile_id` | `bigint not null` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint not null` | - |
| `version_no` | `int not null` | - |
| `document_version_id` | `bigint null` | - |
| `source_type` | `varchar(40` | - |

### `space_knowledge_question`

- Mapper: `FileRagChunkMapper.java`, `SpaceKnowledgeQuestionMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V7__space_knowledge_pipeline.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint not null` | - |
| `question` | `varchar(500` | - |

### `space_member`

- Mapper: `SpaceMapper.java`, `SpaceMemberMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `user_id` | `bigint not null` | - |
| `role` | `varchar(20` | - |

### `space_rag_chunk_ref`

- Mapper: `FileRagChunkMapper.java`, `SpaceRagChunkRefMapper.java`, `SpaceRagDocumentMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `document_id` | `bigint not null` | - |
| `space_file_id` | `bigint not null` | - |
| `file_chunk_id` | `bigint not null` | - |
| `status` | `int not null default 1` | - |
| `createtime` | `timestamp not null` | - |
| `updatetime` | `timestamp not null` | - |

索引/约束：
- `unique key uk_space_rag_chunk_ref (space_id, document_id, file_chunk_id`

### `space_rag_config`

- Mapper: `SpaceRagMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null unique` | - |
| `embedding_model` | `varchar(100` | - |
| `temperature` | `decimal(3,2) not null default 0.20 after top_k', 'select 1' ) )` | - |

迁移补充字段：
- `add column temperature decimal(3,2) not null default 0.20 after top_k', 'select 1' ) )`

### `space_rag_config_log`

- Mapper: `SpaceRagConfigLogMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `operator_id` | `bigint not null` | - |
| `changed_fields` | `varchar(500` | - |

### `space_rag_document`

- Mapper: `FileRagChunkMapper.java`, `SpaceRagDocumentMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `space_file_id` | `bigint not null` | - |
| `file_uuid` | `varchar(64` | - |

### `space_rag_query_log`

- Mapper: `SpaceRagQueryLogMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V6__rag_config_temperature_and_logs.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `user_id` | `bigint not null` | - |
| `question` | `text not null` | - |
| `answer` | `longtext` | - |
| `hit_chunk_ids` | `varchar(1000` | - |
| `top_k` | `int after model_name', 'select 1' ) )` | - |
| `temperature` | `decimal(3,2) after top_k', 'select 1' ) )` | - |

迁移补充字段：
- `add column top_k int after model_name', 'select 1' ) )`
- `add column temperature decimal(3,2) after top_k', 'select 1' ) )`

### `space_rag_task`

- Mapper: `SpaceRagTaskMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/migration/V2__rag_task_progress.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `space_id` | `bigint not null` | - |
| `space_file_id` | `bigint` | - |
| `document_id` | `bigint` | - |
| `task_type` | `varchar(30` | - |
| `total_count` | `int not null default 0 after task_status' ) )` | - |
| `success_count` | `int not null default 0 after total_count' ) )` | - |
| `failed_count` | `int not null default 0 after success_count' ) )` | - |

迁移补充字段：
- `add column total_count int not null default 0 after task_status' ) )`
- `add column success_count int not null default 0 after total_count' ) )`
- `add column failed_count int not null default 0 after success_count' ) )`

### `spaces`

- Mapper: `SpaceMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V1__space_and_rag.sql`, `cloud-server/src/main/resources/db/space.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `name` | `varchar(100` | - |

### `upload_chunk`

- Mapper: `ChunkUploadMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/multipart-upload.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `id` | `bigint primary key auto_increment` | - |
| `upload_id` | `varchar(64` | - |

### `upload_task`

- Mapper: `MultifileMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/migration/V3__multipart_sha1_upload_id.sql`, `cloud-server/src/main/resources/db/multipart-upload.sql`

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `file_sha1` | `varchar(40) after file_md5', 'select 1' ) )` | - |
| `id` | `bigint primary key auto_increment` | - |
| `upload_id` | `varchar(64` | - |

迁移补充字段：
- `add column file_sha1 varchar(40) after file_md5', 'select 1' ) )`

### `user_file`

- Mapper: `FileInfoMapper.java`
- 表注释: -
- 结构来源: `cloud database.md`
- 完整性：未找到完整 `create table`，以下字段来自迁移语句或旧设计文档。

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `ID` | `BIGINT primary key auto_increment` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `file_name` | `varchar(64)` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `file_uuid` | `varchar(64)` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `is_dir` | `int` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `user_id` | `BIGINT` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `parent_id` | `BIGINT` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `path` | `varchar(255)` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `status` | `int` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `createtime` | `timestamp` | 来自早期设计文档，db SQL 中未找到建表语句 |
| `updatetime` | `timestamp` | 来自早期设计文档，db SQL 中未找到建表语句 |

### `users`

- Mapper: `LoginMapper.java`, `SignMapper.java`
- 表注释: -
- 结构来源: `cloud-server/src/main/resources/db/permission-share.sql`
- 完整性：未找到完整 `create table`，以下字段来自迁移语句或旧设计文档。

| 字段 | 类型/约束 | 注释 |
| --- | --- | --- |
| `role` | `varchar(20) not null default 'USER'` | - |

迁移补充字段：
- `add column role varchar(20) not null default 'USER'`
