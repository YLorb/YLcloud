# Space RAG 建表设计

本文档记录空间 RAG 的数据库设计。当前优化目标是：**MinIO 中同一份真实文件只保存一份，文件切片和向量也尽量只生成一份；空间 RAG 通过引用表控制哪些空间可以查询哪些文件 chunk**。

## 1. space_rag_config：空间 RAG 配置表

### 表作用

保存每个空间的 RAG 配置，例如模型、切片大小、召回数量、是否启用。空间仍然拥有独立的配置，但物理文件 chunk 可以被多个空间复用。

### 建表 SQL

```sql
create table if not exists space_rag_config (
    id bigint primary key auto_increment comment '主键 ID',
    space_id bigint not null unique comment '空间 ID，一个空间只允许一条 RAG 配置',
    embedding_model varchar(100) comment '向量化模型名称',
    chat_model varchar(100) comment '问答模型名称，后续接入 langchain4j 时使用',
    vector_collection varchar(100) not null comment '向量库集合名称；使用总向量库时可保存统一集合名',
    chunk_size int not null default 1000 comment '文本分块大小',
    chunk_overlap int not null default 100 comment '文本分块重叠长度',
    top_k int not null default 5 comment '查询时召回的文本块数量',
    score_threshold decimal(6,4) default 0.0000 comment '召回相似度阈值，0 表示不限制',
    enabled int not null default 1 comment 'RAG 是否启用：1 启用，0 停用',
    status int not null default 1 comment '记录状态：1 正常，0 删除或禁用',
    createtime timestamp not null comment '创建时间',
    updatetime timestamp not null comment '更新时间',
    index idx_space_rag_config_space_id (space_id)
) comment '空间 RAG 配置表';
```

## 2. space_rag_document：空间 RAG 文档索引表

### 表作用

记录某个空间中的某个文件是否已经进入 RAG。它表示“空间文件节点”和“物理文件”的索引关系，不直接保存文本 chunk。

### 建表 SQL

```sql
create table if not exists space_rag_document (
    id bigint primary key auto_increment comment '主键 ID',
    space_id bigint not null comment '空间 ID',
    space_file_id bigint not null comment '空间文件节点 ID，对应 space_file.id',
    file_uuid varchar(64) not null comment '物理文件 UUID，对应 MinIO 中的真实文件',
    file_name varchar(255) not null comment '空间内展示的文件名称',
    file_hash varchar(128) comment '物理文件哈希，用于判断是否同一份文件',
    file_type varchar(50) comment '文件类型，例如 pdf、docx、txt、md',
    index_status varchar(20) not null default 'PENDING' comment '索引状态：PENDING、INDEXING、SUCCESS、FAILED',
    chunk_count int not null default 0 comment '当前空间引用的 chunk 数量',
    error_message varchar(1000) comment '索引失败原因',
    created_by bigint not null comment '创建索引的用户 ID',
    status int not null default 1 comment '记录状态：1 正常，0 删除或禁用',
    createtime timestamp not null comment '创建时间',
    updatetime timestamp not null comment '更新时间',
    unique key uk_space_rag_document_file (space_id, space_file_id),
    index idx_space_rag_document_space_id (space_id),
    index idx_space_rag_document_file_uuid (file_uuid),
    index idx_space_rag_document_status (index_status)
) comment '空间 RAG 文档索引表';
```

## 3. file_rag_chunk：物理文件 RAG 文本分块表

### 表作用

保存 MinIO 真实文件解析后的全局文本 chunk。  
同一个 `file_uuid` 只需要切片一次，多个空间通过引用表复用这些 chunk。后续接入 langchain4j 后，`vector_id` 指向总向量库中的向量记录。

### 建表 SQL

```sql
create table if not exists file_rag_chunk (
    id bigint primary key auto_increment comment '主键 ID',
    file_uuid varchar(64) not null comment '物理文件 UUID',
    file_hash varchar(128) not null comment '物理文件哈希，必须与 file_uuid 一起确定一份物理文件版本',
    chunk_index int not null comment '该文件内的 chunk 序号，从 0 开始',
    content longtext not null comment '文本块内容',
    content_hash varchar(128) comment '文本块内容哈希',
    token_count int default 0 comment '文本块 token 数量，暂时无法精确时可存估算值',
    metadata json comment '文本块元数据，例如页码、章节、标题等',
    vector_id varchar(128) comment '总向量库中的向量 ID',
    embedding_model varchar(100) comment '生成该向量时使用的 embedding 模型',
    chunk_size int comment '生成该 chunk 时使用的分块大小',
    chunk_overlap int comment '生成该 chunk 时使用的重叠长度',
    status int not null default 1 comment '记录状态：1 正常，0 删除或禁用',
    createtime timestamp not null comment '创建时间',
    updatetime timestamp not null comment '更新时间',
    unique key uk_file_rag_chunk_index (file_uuid, chunk_index),
    index idx_file_rag_chunk_file_uuid (file_uuid),
    index idx_file_rag_chunk_file_hash (file_hash),
    index idx_file_rag_chunk_vector_id (vector_id)
) comment '物理文件 RAG 文本分块表';
```

## 4. space_rag_chunk_ref：空间 RAG chunk 引用表

### 表作用

保存空间对全局文件 chunk 的引用关系。  
查询某个空间 RAG 时，只能召回该空间在此表中拥有有效引用的 chunk，从而做到“总向量库共享、空间权限隔离”。

### 建表 SQL

```sql
create table if not exists space_rag_chunk_ref (
    id bigint primary key auto_increment comment '主键 ID',
    space_id bigint not null comment '空间 ID',
    document_id bigint not null comment '空间 RAG 文档索引 ID，对应 space_rag_document.id',
    space_file_id bigint not null comment '空间文件节点 ID',
    file_chunk_id bigint not null comment '全局文件 chunk ID，对应 file_rag_chunk.id',
    status int not null default 1 comment '记录状态：1 正常，0 删除或禁用',
    createtime timestamp not null comment '创建时间',
    updatetime timestamp not null comment '更新时间',
    unique key uk_space_rag_chunk_ref (space_id, document_id, file_chunk_id),
    index idx_space_rag_chunk_ref_space_id (space_id),
    index idx_space_rag_chunk_ref_document_id (document_id),
    index idx_space_rag_chunk_ref_space_file_id (space_file_id),
    index idx_space_rag_chunk_ref_file_chunk_id (file_chunk_id)
) comment '空间 RAG chunk 引用表';
```

## 5. space_rag_task：空间 RAG 索引任务表

### 表作用

记录文件导入、文件删除、单文件重建、整个空间重建等任务。后续可以改成异步任务或消息队列处理。

### 建表 SQL

```sql
create table if not exists space_rag_task (
    id bigint primary key auto_increment comment '主键 ID',
    space_id bigint not null comment '空间 ID',
    space_file_id bigint comment '空间文件节点 ID',
    document_id bigint comment 'RAG 文档索引 ID',
    task_type varchar(30) not null comment '任务类型：INDEX_FILE、REBUILD_FILE、REBUILD_SPACE、DELETE_FILE',
    task_status varchar(20) not null default 'PENDING' comment '任务状态：PENDING、RUNNING、SUCCESS、FAILED',
    error_message varchar(1000) comment '任务失败原因',
    created_by bigint not null comment '创建任务的用户 ID',
    started_time timestamp null comment '任务开始时间',
    finished_time timestamp null comment '任务结束时间',
    createtime timestamp not null comment '创建时间',
    updatetime timestamp not null comment '更新时间',
    index idx_space_rag_task_space_id (space_id),
    index idx_space_rag_task_space_file_id (space_file_id),
    index idx_space_rag_task_status (task_status),
    index idx_space_rag_task_type (task_type)
) comment '空间 RAG 索引任务表';
```

## 6. space_rag_query_log：空间 RAG 问答日志表

### 表作用

记录用户在空间内的 RAG 查询历史，可用于查看历史问答、分析召回效果、优化 RAG 配置。

### 建表 SQL

```sql
create table if not exists space_rag_query_log (
    id bigint primary key auto_increment comment '主键 ID',
    space_id bigint not null comment '空间 ID',
    user_id bigint not null comment '提问用户 ID',
    question text not null comment '用户问题',
    answer longtext comment '模型回答',
    hit_chunk_ids varchar(1000) comment '命中的 file_rag_chunk ID 列表，使用英文逗号分隔',
    model_name varchar(100) comment '实际使用的问答模型名称',
    prompt_tokens int default 0 comment '提示词 token 数量',
    completion_tokens int default 0 comment '回答 token 数量',
    total_tokens int default 0 comment '总 token 数量',
    success int not null default 1 comment '是否成功：1 成功，0 失败',
    error_message varchar(1000) comment '失败原因',
    createtime timestamp not null comment '创建时间',
    index idx_space_rag_query_log_space_id (space_id),
    index idx_space_rag_query_log_user_id (user_id),
    index idx_space_rag_query_log_createtime (createtime)
) comment '空间 RAG 问答日志表';
```

## 核心流程

1. 文件上传到 MinIO 后，`file_info` 记录物理文件信息。
2. 文件加入某个 space 后，`space_rag_document` 记录空间文件进入 RAG。
3. 如果 `file_rag_chunk` 中没有该 `file_uuid` 的 chunk，则解析 MinIO 文件、切片、向量化并写入总向量库。
4. `space_rag_chunk_ref` 记录当前 space 可以使用哪些全局 chunk。
5. 查询 RAG 时，先根据 `space_id` 限定引用范围，再从总向量库或 `file_rag_chunk` 中召回内容。
6. 删除空间文件时，只禁用 `space_rag_chunk_ref` 和 `space_rag_document`，不删除 MinIO 文件和全局 chunk。

## 7. file_version：文件历史版本表

### 表作用

用于记录基于 MinIO Object Versioning 的业务历史版本。MinIO 负责保存真实对象的多个版本，业务表负责展示版本号、上传人、文件大小、哈希、说明以及当前版本标记。

### 建表 SQL

```sql
create table if not exists file_version (
    id bigint primary key auto_increment comment '主键 ID',
    file_uuid varchar(64) not null comment '物理文件 UUID，也是 MinIO object name',
    version_no int not null comment '业务版本号，从 1 递增',
    minio_version_id varchar(255) not null comment 'MinIO 对象版本 ID',
    file_name varchar(255) not null comment '该版本文件名',
    file_hash varchar(128) comment '该版本 SHA-256 哈希',
    file_md5 varchar(128) comment '该版本 MD5',
    file_type varchar(50) comment '该版本文件类型',
    file_size bigint comment '该版本文件大小',
    change_note varchar(500) comment '版本说明',
    created_by bigint not null comment '创建该版本的用户 ID',
    is_current int not null default 0 comment '是否当前版本：1 是，0 否',
    status int not null default 1 comment '记录状态：1 正常，0 删除',
    createtime timestamp not null comment '创建时间',
    unique key uk_file_version_no (file_uuid, version_no),
    index idx_file_version_file_uuid (file_uuid),
    index idx_file_version_minio_version_id (minio_version_id),
    index idx_file_version_current (file_uuid, is_current)
) comment '文件历史版本表';
```

## 历史版本开关

空间默认开关放在 `spaces.version_enabled`，单文件覆盖开关放在 `space_file.version_enabled`。

判断最终是否开启：

```text
space_file.version_enabled 不为空：使用文件自己的设置
space_file.version_enabled 为空：继承 spaces.version_enabled
```

相关字段：

```sql
alter table spaces add column version_enabled int not null default 1 comment '空间是否默认维护文件历史版本：1 是，0 否';
alter table space_file add column version_enabled int null comment '空间文件是否维护历史版本：1 是，0 否，null 继承空间设置';
```
