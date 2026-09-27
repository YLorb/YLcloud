-- Upgrade spaces set to 5, including the former default; preserve other values.
update space_rag_config set top_k = 10 where top_k = 5;
alter table space_rag_config modify column top_k int not null default 10;
