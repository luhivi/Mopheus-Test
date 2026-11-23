# Mopheus-Test

在客户使用之前，我先来自己试用一下 Mopheus 写代码的能力，以及弄清楚整个流程到底是如何

## taskboard：任务登记样例

`taskboard` 是一个最小可用的全栈样例：Spring Boot 3 提供 REST API，前端是零框架、零构建链的原生 HTML / CSS / JavaScript，静态资源由 Spring 直接托管。

它被用来验证 Mopheus 的完整交付链路：建工单 → 方案对抗审查 → 编码 → 代码挑刺审查 → 提交 GitHub。

### 技术要求（实测版本）

| 项 | 版本 |
| --- | --- |
| JDK | 17.0.12（arm64） |
| Maven | 3.9.9（由仓库自带的 `./mvnw` 下载，无需全局安装） |
| Spring Boot | 3.3.5 |
| 前端 | 原生 HTML5 / CSS3 / ES6+，无 Node、无构建链 |

### 运行

```bash
./mvnw test              # 13 个测试：9 个 MockMvc 接口测试 + 4 个 Service 单测
./mvnw spring-boot:run   # 默认 http://localhost:8080
```

只有 JDK 17 是前提。Maven 由 wrapper 在首次运行时下载 3.9.9，不需要预装 `mvn`。

打开 <http://localhost:8080/> 即为任务板页面。

### 接口

| 方法 | 路径 | 入参 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| GET | `/api/tasks` | — | 200 任务列表（新→旧） | — |
| POST | `/api/tasks` | `{"title": "..."}` | 201 + 任务 | 400 校验失败 |
| PATCH | `/api/tasks/{id}/status` | `{"status": "TODO\|DOING\|DONE"}` | 200 + 任务 | 400 非法枚举 / 404 不存在 |
| DELETE | `/api/tasks/{id}` | — | 204 | 404 不存在 |

错误体统一为 `{"code": "...", "message": "...", "timestamp": "..."}`，`message` 是白名单文案，不返回堆栈、SQL 或内部类名。框架自身的状态码（404 无此路由、405 方法不支持、415 媒体类型不支持）会原样保留，不会被兜底处理器压成 500。

### 已知限制

- **数据存在内存里**：重启即清空，也不能多实例部署。要持久化需要接数据库。
- 没有鉴权，任何人可读写，仅作样例。
- 没有分页，任务量大时需要补。
- 标题原样存储，不转义；显示端用 `textContent` 写入，所以不会执行，但接口消费者需要自行处理。

### 目录

```
pom.xml
mvnw / mvnw.cmd / .mvn/wrapper/
src/main/java/com/example/taskboard/
  TaskboardApplication.java
  model/{Task,TaskStatus}.java
  service/{TaskService,TaskNotFoundException}.java
  web/{TaskController,ApiExceptionHandler,CreateTaskRequest,UpdateStatusRequest}.java
src/main/resources/
  application.properties
  static/{index.html,style.css,app.js}
src/test/java/com/example/taskboard/
  TaskApiTest.java
  service/TaskServiceTest.java
```
