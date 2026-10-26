CREATE TABLE short_link_node (
    node_id SMALLINT NOT NULL PRIMARY KEY,
    owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    CHECK (node_id BETWEEN 0 AND 1023)
);
