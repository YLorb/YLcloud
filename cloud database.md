## 一、用户表（users�?

|    名称     |     类型     |    备注     |
| :---------: | :----------: | :---------: |
|   userID    | varchar(50)  |  （主键）   |
|  username   | varchar(50)  |             |
|  password   | varchar(50)  |             |
|  nickname   | varchar(50)  |             |
|   rootDir   |    BIGINT    |   unique    |
|    头像     | varchar(100) |     url     |
|    email    | varchar(100) | url，冗余键 |
|   status    |     bool     |             |
| create_time |  timestamp   |             |
| update_time |  timestamp   |             |



## 二、文件信息表（files_info�?

同表3关联数据：file_uuid

|     英文      |      名称       |       类型       |            备注            |
| :-----------: | :-------------: | :--------------: | :------------------------: |
|    file_id    |     文件 ID     |    **BIGINT**    |    主键,unique,not null    |
|   file_uuid   |   文件唯一 id   |   varchar(64)    |   冗余�?unique,not null   |
|  ~~user_id~~  | ~~所属用�?id~~ |  ~~**BIGINT**~~  |        ~~not null~~        |
| ~~parent_id~~ |  ~~父目�?id~~  |  ~~**BIGINT**~~  |        ~~保留中。~~        |
|     name      |     文件�?     |   varchar(255)   |          not null          |
|     type      |    文件类型     |   varchar(100)   |        default .txt        |
|     size      |    文件大小     |    **BIGINT**    |         default 0          |
|   ~~path~~    |  ~~存储路径~~   | ~~varchar(512)~~ |                            |
|      md5      |     文件MD5     | **varchar(32)**  |             -              |
|     hash      |   文件哈希�?   |   varchar(64)    |             -              |
|    status     |      状�?      |       int        |          not null          |
|  createtime   |    创建时间     |    timestamp     |          not null          |
|  updatetime   |    修改时间     |    timestamp     |          not null          |



## 三、用�?- 文件�?user_file)

此表关联用户与其所属文件的 uuid

（同�?关联数据：file_uuid�?

（同�?关联数据：user_id - userID�?

|    英文    |    名称     |     类型     |            备注            |
| :--------: | :---------: | :----------: | :------------------------: |
|     ID     |   自增ID    |  **BIGINT**  |    主键,unique,not null    |
| file_name  |   文件�?   | varchar(64)  |          not null          |
| file_uuid  | 文件唯一 id | varchar(64)  |   冗余�?unique,not null   |
|   is_dir   | 文件/文件�?|     int      | 0：文�?1：文件夹,not null |
|  user_id   | 所属用�?id |  **BIGINT**  |          not null          |
| parent_id  |  父目�?id  |  **BIGINT**  |                            |
|    path    |    路径     | varchar(255) |                            |
|   status   |    状�?    |     int      |          not null          |
| createtime |  创建时间   |  timestamp   |          not null          |
| updatetime |  修改时间   |  timestamp   |          not null          |
