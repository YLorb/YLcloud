-- Older chunks already have database IDs. Expose them and a nullable single-page
-- location in the JSON envelope without changing their original page range.
update file_rag_chunk
set metadata = json_set(
    coalesce(metadata, json_object()),
    '$.chunkId', id,
    '$.chunk_id', id,
    '$.page', case
        when json_extract(metadata, '$.pageStart') = json_extract(metadata, '$.pageEnd')
            then json_extract(metadata, '$.pageStart')
        else null
    end
);
