-- mysqld-exporter가 perf_schema digest 통계 / processlist / replication 상태를 읽으려면
-- 일반 DB 권한 외 PROCESS, REPLICATION CLIENT, performance_schema SELECT 권한이 필요하다.
-- docker-entrypoint-initdb.d에 마운트되어 root 권한으로 실행되므로 GRANT가 적용된다.

GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'hellopani'@'%';
GRANT SELECT ON performance_schema.* TO 'hellopani'@'%';
FLUSH PRIVILEGES;
