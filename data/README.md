# 生产数据目录

此目录只保存服务器本地持久化数据，不提交真实数据文件：

- `postgres/` 挂载到 PostgreSQL 18 的 `/var/lib/postgresql`
- `redis/` 挂载到 Redis 的 `/data`
- `rabbitmq/` 挂载到 RabbitMQ 的 `/var/lib/rabbitmq`

三个子目录必须由运维人员在启动 Compose 前显式创建并设置正确所有者。Compose 使用 `create_host_path: false`，目录缺失时会直接失败，避免拼错路径后静默创建空数据库。
