# Mopheus-Test · taskboard

一个最小可用的全栈任务板，用来验证 Mopheus 平台的完整代码交付链路：
**建工单 → 方案对抗审查 → 编码 → 代码挑刺审查 → PR 合入**。

前端零框架、零构建链，后端是标准的 Spring Boot 单体，静态资源由 Spring 直接托管。
一份 `./mvnw spring-boot:run` 就能起，打开浏览器即可用。

---

## 这个项目是干什么的

一个任务登记页：新建任务、把任务从「待办」推到「进行中」再到「已完成」、删除任务。

它刻意做得很小，因为目的不是做产品，而是给 Mopheus 的编码流程提供一块**可验收的试金石**：
需求能从接口契约反向验证，代码能被真实跑起来，缺陷能被审查流程抓出来。

它确实抓出来了 —— 见下面的[对抗审查记录](#对抗审查记录)。

---

## 技术栈

| 层 | 技术 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java | 17 (LTS) | `pom.xml` 中 `java.version=17` |
| 框架 | Spring Boot | 3.5.16 | 3.x 最新补丁版 |
| Web | Spring MVC | 随 Boot | `@RestController` + `@RestControllerAdvice` |
| 校验 | Jakarta Bean Validation | 随 Boot | `@Valid` + `@NotBlank` / `@Size` / `@NotNull` |
| JSON | Jackson | 随 Boot | 开启严格反序列化，拒绝隐式强转 |
| 构建 | Maven | 3.9.9 | 由仓库自带 `mvnw` 下载，无需全局安装 |
| 测试 | JUnit 5 + MockMvc | 随 Boot | 18 个用例 |
| 前端 | 原生 HTML5 / CSS3 / ES6+ | — | **不引入任何框架与构建链**，无 Node 依赖 |

**只需要 JDK 17。** Maven 由 wrapper 自举，npm / node 完全不需要。

---

## 快速开始

```bash
# 1. 跑测试
./mvnw test

# 2. 启动（默认 8080）
./mvnw spring-boot:run

# 3. 打开浏览器
open http://localhost:8080/
```

打包成可执行 jar：

```bash
./mvnw package -DskipTests
java -jar target/taskboard-0.0.1-SNAPSHOT.jar
```

换端口：

```bash
java -jar target/taskboard-0.0.1-SNAPSHOT.jar --server.port=9090
```

---

## 接口

所有接口都在 `/api/tasks` 下，只接受和返回 `application/json`。

| 方法 | 路径 | 请求体 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/tasks` | — | `200` 任务数组（新 → 旧） | — |
| `POST` | `/api/tasks` | `{"title":"..."}` | `201` + 任务对象 | `400` 校验失败 |
| `PATCH` | `/api/tasks/{id}/status` | `{"status":"TODO\|DOING\|DONE"}` | `200` + 任务对象 | `400` 非法值 / `404` 不存在 |
| `DELETE` | `/api/tasks/{id}` | — | `204` | `404` 不存在 |

任务对象：

```json
{
  "id": 1,
  "title": "补齐接口文档",
  "status": "TODO",
  "createdAt": "2026-11-23T01:50:49.339712Z"
}
```

### 校验规则

| 字段 | 规则 | 违反时 |
| --- | --- | --- |
| `title` | 必填，非空白，最长 80 字符，**必须是字符串** | `400` |
| `status` | 必须是 `TODO` / `DOING` / `DONE` 三者之一 | `400` |

`{"title":123}`、`{"status":2}` 这类会被默认 Jackson 静默强转的输入一律拒绝 ——
契约写 string 就只收 string，不靠前端保证。

### 错误响应

所有失败路径统一为：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "title must not be blank",
  "timestamp": "2026-11-23T01:50:49.365959Z"
}
```

| code | 状态码 | 场景 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 字段校验不通过 |
| `MALFORMED_BODY` | 400 | JSON 语法错误 / 枚举值不存在 |
| `TASK_NOT_FOUND` | 404 | 任务 id 不存在 |
| `NOT_FOUND` | 404 | 路由不存在 |
| `METHOD_NOT_ALLOWED` | 405 | HTTP 方法不支持（响应带 `Allow` 头） |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` 不是 JSON |
| `NOT_ACCEPTABLE` | 406 | `Accept` 不接受 JSON |
| `INTERNAL_ERROR` | 500 | 未预期异常 |

`message` 是固定白名单文案，**不返回堆栈、SQL、表名或内部类名**。
无论客户端 `Accept` 什么，错误体都是 JSON —— 不会退化成 HTML 错误页。

---

## 项目结构

```
Mopheus-Test/
├── pom.xml
├── mvnw / mvnw.cmd / .mvn/wrapper/          Maven wrapper（自举 3.9.9）
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/taskboard/
    │   │   ├── TaskboardApplication.java      启动类 + Clock Bean
    │   │   ├── model/
    │   │   │   ├── Task.java                  不可变 record
    │   │   │   └── TaskStatus.java            TODO / DOING / DONE
    │   │   ├── service/
    │   │   │   ├── TaskService.java           内存存储 + 并发安全
    │   │   │   └── TaskNotFoundException.java
    │   │   └── web/
    │   │       ├── TaskController.java        REST 入口
    │   │       ├── ApiExceptionHandler.java   统一错误体
    │   │       ├── JacksonStrictConfig.java   严格反序列化
    │   │       ├── CreateTaskRequest.java
    │   │       └── UpdateStatusRequest.java
    │   └── resources/
    │       ├── application.properties
    │       └── static/
    │           ├── index.html                 页面结构
    │           ├── style.css                  样式
    │           └── app.js                     交互逻辑
    └── test/java/com/example/taskboard/
        ├── TaskApiTest.java                   14 个 MockMvc 接口用例
        └── service/TaskServiceTest.java       4 个 Service 单测
```

### 设计要点

**并发安全**：`ConcurrentHashMap<Long, Task>` 存数据，`AtomicLong` 发 id。
改状态用 `computeIfPresent` 原子替换，`Task` 是不可变 record，读到的永远是完整快照。
`findAll` 显式按 id 倒序排，不依赖哈希表迭代顺序。

**错误处理**：`ApiExceptionHandler` 继承 `ResponseEntityExceptionHandler`，
保留 Spring 自己的 404 / 405 / 415 语义，只替换错误体格式。
所有响应显式钉死 `application/json`，避免内容协商失败把 404 降级成 500。

**前端三态**：加载中 / 空数据 / 请求失败各自独立呈现，互不覆盖。
用户输入一律用 `textContent` 写入，不存在 `innerHTML` 拼接。
请求带 8 秒超时，行内操作有 busy 标志防连点，加载有请求编号防旧响应覆盖新结果。

---

## 测试

```bash
./mvnw test
```

```
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：4 个接口的正常路径、400 / 404 / 405 / 406 / 415 边界、
枚举 ordinal 拒绝、非字符串 title 拒绝、错误体不泄露内部信息。

---

## 对抗审查记录

这个样例经过了 Mopheus 平台的两段式对抗审查（方案期 + 交付期）。
审查者独立 checkout、实跑构建与测试，抓出的缺陷如下，全部已修复并带回归用例：

| 级别 | 问题 | 后果 |
| --- | --- | --- |
| P0 | `.task-list { display:flex }` 压过 `[hidden]` 属性 | 空态 / 错误态叠在空列表上，页面三态切换完全失效 |
| P1 | 业务 404 在 `Accept: text/html` 时无法协商 | Spring 把 404 降级成 **500 Whitelabel**，且绕过 `@ExceptionHandler(Exception.class)` |
| P1 | 405 响应丢失 `Allow` 头 | 违反 RFC 9110 §15.5.6 |
| P1 | 405 / 415 被压成 `BAD_REQUEST` | 状态码语义失真 |
| P1 | `deleteTask` 失败时重新启用已完成行的「推进」按钮 | 用户可发出无意义的 PATCH |
| P1 | 前端加载无请求编号 | 并发加载时旧响应覆盖新列表 |
| P2 | `{"status":2}` 按枚举 ordinal 解析成 `DONE` | 静默接受契约外的输入 |
| P2 | `{"title":123}` 被强转成 `"123"` | 同上 |

---

## 已知限制

这是一个**样例**，不是生产组件。以下限制是有意为之：

- **数据存在内存里**：重启即清空，也不支持多实例部署。要持久化需接入数据库。
- **没有鉴权**：任何人可读写全部任务。
- **没有分页**：任务量大时需要补。
- **标题原样存储、不转义**：显示端用 `textContent` 写入所以不会执行，
  但如果把接口给别的消费者用，需要自行处理。

---

## 许可

仅用于 Mopheus 平台内部验证，未指定开源许可。
