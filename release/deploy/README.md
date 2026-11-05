# BeModel 服务器部署

部署目录：`/home/zhulinqiang/bemodel`。所有容器使用 `unix:///run/docker-eval.sock`，独立 Compose 项目名为 `bemodel`。

## 访问与账号

- HTTP：`http://118.196.39.11:18081`，直接提供页面和接口，不跳转 HTTPS。
- 当前未启用 HTTPS，不发布 18443 端口。
- 演示账号已恢复项目原始密码：`admin/admin123`、`modeler/model123`、`viewer/viewer123`、`reviewer/reviewer123`。服务器 `initial-accounts.json` 已同步。
- 后端和 MySQL 不发布宿主机端口。未鉴权的 `/mcp` 被 Nginx 阻断。

## 运行与更新

```bash
cd /home/zhulinqiang/bemodel
./compose.sh ps
./compose.sh logs --tail=100 backend
./compose.sh logs -f --tail=100 web
```

更新前先执行 `./backup.sh`，保留旧 JAR 和前端文件。后端新 JAR 替换到 `backend/bemodel-server-1.0.0.jar` 后，使用 `./compose.sh up -d --force-recreate backend`。前端新文件放入 `frontend/`，保持目录 755、静态文件 644。涉及数据库迁移的版本回滚需要同时评估数据库兼容性。

`compose.sh` 直接调用已安装的 Compose 插件，解决非交互 SSH 下 Docker CLI 未发现插件的问题，不修改服务器全局 Docker 配置。镜像已预拉取，`pull_policy: never` 避免启动时访问不可达的仓库；当前镜像信息另见服务器上的 `image-manifest.json`。

## 数据与密钥

本环境按要求从空数据库启动，Flyway 创建平台库和 9 个业务库，项目生成演示数据；没有导入或更改源数据库。MySQL 数据保存在 `bemodel_mysql_data` 数据卷中。不要执行 `down -v` 或清理该数据卷。

`.env` 保存独立的数据库口令、JWT 和数据源加密密钥。它与 `initial-accounts.json` 均为敏感文件，应另存受控备份。已有数据源密码使用 `APP_SECRET_KEY` 加密，不能在保留数据的情况下随意更换它。

当前没有配置 AI API Key，相关能力使用应用的降级逻辑。需要 AI 时，在服务器 `.env` 中设置 `DEEPSEEK_API_KEY`，按实际服务商配置 `DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`，然后执行 `./compose.sh up -d backend`。

## 备份与验证

`./backup.sh` 使用事务快照备份本项目的数据库，输出 gzip 文件到 `backups/` 并检查压缩包完整性。每日 03:40（服务器 Asia/Shanghai 时区）由用户 crontab 执行，日志为 `backup.log`。这是同机备份，重要数据应另行复制到其他存储。

初次部署验证记录在服务器 `verification.json`，改用 HTTP 后的验证记录在 `verification-http.json`。恢复默认密码后的最新账号登录验证及数据库实际表数、行数记录在 `verification-data.json`；四个默认账号均验证登录成功。已验证外网 HTTP 健康接口返回 200。未进行交互浏览器的完整业务验收。
