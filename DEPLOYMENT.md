# 容器化部署

## 前置条件

- Docker Engine 与 Docker Compose v2
- 对本机 `8081`（应用）和 `15672`（RabbitMQ 管理页）的访问权限

## 启动

```bash
cp .env.example .env
# 编辑 .env，将所有 replace-with-* 的值替换为强密码
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

首次启动时，MySQL 会自动执行 `src/main/resources/db/hmdp.sql`，创建并填充 `hmdp` 数据库。

- API：`http://localhost:8081`
- RabbitMQ 管理页：`http://localhost:15672`

## 常用维护命令

```bash
docker compose logs -f app
docker compose restart app
docker compose down
```

`docker compose down` 不会删除数据卷。若确实需要清空数据库、缓存、消息和上传文件后重新初始化，执行：

```bash
docker compose down -v
docker compose up --build -d
```

> 清空卷会永久删除本项目容器中的数据。
