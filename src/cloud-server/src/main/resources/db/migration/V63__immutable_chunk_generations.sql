alter table file_rag_chunk add column generation varchar(36) not null default 'legacy';
alter table file_rag_chunk drop index uk_file_rag_chunk_index;
alter table file_rag_chunk add unique key uk_file_rag_chunk_generation (file_uuid,file_hash,generation,chunk_index);
