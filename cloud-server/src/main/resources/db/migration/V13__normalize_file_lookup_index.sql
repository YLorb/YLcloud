drop index idx_file_info_md5_sha1_size on file_info;

create index idx_file_info_md5_sha1_size
    on file_info (md5, sha1, size, status);
