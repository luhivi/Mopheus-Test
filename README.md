# Mopheus-Test

在客户使用之前，我先来自己试用一下 Mopheus 写代码的能力，以及弄清楚整个流程到底是如何

## taskboard：任务登记样例

`taskboard` 是一个最小可用的全栈样例：Spring Boot 3 提供 REST API，前端是零框架、零构建链的原生 HTML / CSS / JavaScript，静态资源由 Spring 直接托管。

它被用来验证 Mopheus 的完整交付链路：建工单 → 方案对抗审查 → 编码 → 代码挑刺审查 → 提交 GitHub。

### 技术要求（实测版本）

| 项 | 版本 |
| --- | --- |
| JDK | 17.0.12（arm64） |
| Maven | 3.9.9 |
| Spring Boot | 3.3.5 |
| 前端 | 原生 HTML5 / CSS3 / ES6+，无 Node、无构建链 |

### 运行

```bash
mvn test              # 11 个测试：7 个 MockMvc 接口测试 + 4 个 Service 单测
mvn spring-boot:run   # 默认 http://localhost:8080
```

打开 <http://localhost:8080/> 即为任务板页面。

### 接口

| 方法 | 路径 | 入参 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| GET | `/api/tasks` | — | 200 任务列表（新→旧） | — |
| POST | `/api/tasks` | `{"title": "..."}` | 201 + 任务 | 400 校验失败 |
| PATCH | `/api/tasks/{id}/status` | `{"status": "TODO\|DOING\|DONE"}` | 200 + 任务 | 400 非法枚举 / 404 不存在 |
| DELETE | `/api/tasks/{id}` | — | 204 | 404 不存在 |

错误体统一为 `{"code": "...", "message": "...", "timestamp": "..."}`，`message` 是白名单文案，不返回堆栈、SQL 或内部类名。

### 已知限制

- **数据存在内存里**：重启即清空，也不能多实例部署。要持久化需要接数据库。
- 没有鉴权，任何人可读写，仅作样例。
- 没有分页，任务量大时需要补。

### 目录

```
pom.xml
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
