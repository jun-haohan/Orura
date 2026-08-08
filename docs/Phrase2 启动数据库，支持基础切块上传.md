# 一、阶段目标

# 

| 时间  | 任务                   | 目标                                               |
| ----- | ---------------------- | -------------------------------------------------- |
| Day 1 | Docker Compose         | 启动 MySQL、MongoDB、Redis                         |
| Day 2 | Milvus + Elasticsearch | 启动向量库和搜索引擎                               |
| Day 3 | SpringBoot 连接中间件  | 后端能连 MySQL、MongoDB、Redis                     |
| Day 4 | 文件上传模块           | 支持上传 PDF / Word / Markdown / TXT               |
| Day 5 | 文档解析接口           | 先实现 TXT / Markdown，PDF / Word 预留接口         |
| Day 6 | Chunk 切分             | 实现 Recursive Chunk、Markdown Header Split 简化版 |
| Day 7 | 测试 + 文档            | 跑通完整 ingestion 流程，记录问题                  |

# 二、当前环境

mysql

mongodb

redis

springboot 4.1.0

# 三、阶段一

## 1. 目标：通过docker安装并启动MySQL、MongoDB、Redis

## 2. 操作：修改docker配置文件，通过docker命令安装

## 3.过程记录

1. 修改docker-compose.yml，增加三个容器的配置

   ```
   services:
     mysql:
       image: mysql:8.4
       container_name: akp-mysql
       environment:
         MYSQL_ROOT_PASSWORD: root
         MYSQL_DATABASE: akp
       ports:
         - "3306:3306"
       volumes:
         - ./data/mysql:/var/lib/mysql
   
     mongodb:
       image: mongo:7
       container_name: akp-mongodb
       ports:
         - "27017:27017"
       volumes:
         - ./data/mongodb:/data/db
   
     redis:
       image: redis:7
       container_name: akp-redis
       ports:
         - "6379:6379"
       command: redis-server --appendonly yes
       volumes:
         - ./data/redis:/data
   ```

2. 手动创建文件结构

   ```
   | docker
   | docker-compose.yml
   | | data
   | | | elasticsearch
   | | | milvus
   | | | | etcd
   | | | | minio
   | | | | milvus
   ```

   

3. 发现docker无法连接源，下载失败。为WSL和Docker开启代理功能。

4. WSL开启代理

   > 1. 代理软件打开系统代理，允许局域网连接
   >
   > 2. 更新WSL
   >
   >    PowerShell 执行：
   >
   >    ```PowerShell
   >    wsl --update
   >    wsl --version
   >    ```
   >
   > 3. 配置 %UserProfile%\.wslconfig
   >
   >    PowerShell 执行：
   >
   >    ```
   >    notepad $env:USERPROFILE\.wslconfig
   >    ```
   >
   >    写入：
   >
   >    ```
   >    [wsl2]
   >    networkingMode=mirrored
   >    dnsTunneling=true
   >    autoProxy=true
   >    firewall=true
   >    
   >    [experimental]
   >    hostAddressLoopback=true
   >    ignoredPorts=53
   >    ```
   >
   > 4. 重启WSL
   >
   >    PowerShell 执行：
   >
   >    ```
   >    wsl --shutdown
   >    ```
   >
   > 5. 测试
   >
   >    WSL 内执行：
   >
   >    ```
   >    curl -I https://www.google.com
   >    curl -x http://127.0.0.1:7890 -I https://www.google.com
   >    ```

5. Docker开启代理

   > 适用于WSL内的Docker
   >
   > 1. 配置 daemon 代理
   >
   >    ```
   >    sudo mkdir -p /etc/docker
   >    
   >    sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
   >    {
   >    "proxies": {
   >     "http-proxy": "http://127.0.0.1:7890",
   >     "https-proxy": "http://127.0.0.1:7890",
   >     "no-proxy": "localhost,127.0.0.1,::1,*.local"
   >    }
   >    }
   >    EOF
   >    
   >    sudo systemctl restart docker
   >    ```
   >
   >    Docker 官方说明：Docker Engine 可通过 `daemon.json` 配置 daemon 代理，修改后需要重启 daemon；但 Docker Desktop 会忽略这种 `daemon.json` 代理配置，应使用 Docker Desktop 设置。
   >
   > 2. 配置容器默认代理
   >
   >    ```
   >    mkdir -p ~/.docker
   >                                     
   >    cat > ~/.docker/config.json <<'EOF'
   >    {
   >    "proxies": {
   >     "default": {
   >       "httpProxy": "http://127.0.0.1:7890",
   >       "httpsProxy": "http://127.0.0.1:7890",
   >       "noProxy": "localhost,127.0.0.1,::1,*.local"
   >     }
   >    }
   >    }
   >    EOF
   >    ```

6. 重新使用`docker compose up -d`安装，使用`docker ps`检查容器启动状态。

## 4. 结果

> 安装并成功启动MySQL、MongoDB、Redis
>
> 配置WSL和Docker的代理功能

# 四、阶段二

## 1. 目标：安装es，milvus

## 2. 操作：通过docker命令安装

## 3. 过程记录

1. 修改docker-compose.yml，增加es和milvus的配置信息。

2. 使用`docker compose up -d`安装，使用`docker ps`检查容器启动状态。

3. BUG：es启动失败

   > ```docker logs akp-elasticsearch```查看ES的日志，检查access、memory等关键词
   >
   > 日志出现AccessDeniedException，确定为权限问题。
   >
   > 分析：ES 容器内默认用户不是 root，无法写入挂载的本地目录。
   >
   > 解决：在docker目录执行
   >
   > ```
   > docker compose down
   > 
   > # 修复权限
   > sudo chown -R 1000:1000 ./data/elasticsearch
   > sudo chmod -R 775 ./data/elasticsearch
   > 
   > # 测试
   > curl http://localhost:9200
   > ```

4. BUG：milvus启动失败，报错9091端口已被占用

   > 修改为9092、9098、9099等多个端口依然提示已经被占用
   >
   > 
   >
   > 判断为不是这些端口真的都被占用，而是Docker / WSL 端口转发状态异常，或 Windows 侧占用了端口段。
   >
   > 决定直接删除 9091 端口映射（9091 主要是 metrics / health，不影响 Java / Python 连接 Milvus。）
   >
   > 删除docker-compose.yml文件中milvus中的ports的"9091:9091"
   >
   > 
   >
   > 重新启动

5. git提交到开发分支

   ```
   git add xxx/*
   git commit -m "feat:安装es和milvus"
   git push origin feature/week2-ingestion
   ```

## 4. 结果

> es和milvus成功启动

# 五、阶段三

## 1. 目标：SpringBoot连接中间件

## 2. 操作：springboot后端连接mysql、mongodb、redis、es和milvus

## 3. 过程记录：

1. 在admin-server的application.yml配置连接

   ```
   spring:
     server:
       port: 8080
   
     spring:
       application:
         name: admin-server
         
     datasource:
       url: jdbc:mysql://localhost:3306/akp?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
       username: root
       password: root
   
     data:
       mongodb:
         uri: mongodb://localhost:27017/akp
   
       redis:
         host: localhost
         port: 6379
   
     elasticsearch:
       uris: http://localhost:9200
   ```

2. 写一批接口检查连接状态

   在admin-server的backend文件夹下创建controller/HealthController.java

   ```
   package com.junhaohan.backend.controller;
   
   
   import org.springframework.web.bind.annotation.GetMapping;
   import org.springframework.web.bind.annotation.RestController;
   
   @RestController
   public class HealthController {
   
       @GetMapping("/health")
       public String health() {
           return "ok";
       }
   }
   ```

3. BUG：启动BackendApplication，报错：Error: JMX connector server communication error: service:jmx:rmi://0.0.0.0:9901

   > 初步判断为接口被占用，但检查发现接口未被占用，且每次重启，显示的接口均不同
   >
   > 判断为JMX 远程监控端口被占用
   >
   > 
   >
   > 无效：在BackendApplication的设置中关闭“启用JMX身份验证”/“Enable JMX agent”
   >
   > 无效：删除BackendApplication的运行配置，重新运行
   >
   > 无效：寻找相关的环境变量和模板，未找到
   >
   > 无效：全项目搜索JMX相关配置
   >
   > 有效：在wsl使用wsl maven启动，`mvn spring-boot:run -pl admin-server`
   >
   > 有效：改用Application启动

4. 测试接口生效  `curl http://localhost:8080/health`

5. 在admin/pom.xml增加依赖

   ```
   		<!-- Web -->
   		<dependency>
   			<groupId>org.springframework.boot</groupId>
   			<artifactId>spring-boot-starter-web</artifactId>
   		</dependency>
   
   		<!-- MySQL -->
   		<dependency>
   			<groupId>com.mysql</groupId>
   			<artifactId>mysql-connector-j</artifactId>
   		</dependency>
   
   		<!-- MongoDB -->
   		<dependency>
   			<groupId>org.springframework.boot</groupId>
   			<artifactId>spring-boot-starter-data-mongodb</artifactId>
   		</dependency>
   
   		<!-- Redis -->
   		<dependency>
   			<groupId>org.springframework.boot</groupId>
   			<artifactId>spring-boot-starter-data-redis</artifactId>
   		</dependency>
   
   		<!-- Elasticsearch -->
   		<dependency>
   			<groupId>org.springframework.boot</groupId>
   			<artifactId>spring-boot-starter-data-elasticsearch</artifactId>
   		</dependency>
   		<dependency>
   			<groupId>org.projectlombok</groupId>
   			<artifactId>lombok</artifactId>
   			<scope>provided</scope>
   		</dependency>
   
   		<!-- JdbcTemplate -->
   		<dependency>
   			<groupId>org.springframework.boot</groupId>
   			<artifactId>spring-boot-starter-jdbc</artifactId>
   		</dependency>
   ```

6. 刷新依赖，在admin目录执行`mvn clean install -DskipTests`

7. 依赖已经导入，但是接口类中对应的依然为红色

   > 命令行当前文件夹尝试`mvn clean install -DskipTests`能通过，说明 Maven 没问题，是 IDEA 缓存/模块识别问题。
   >
   > Maven 面板选择“Reload All Maven Projects”重新加载maven
   >
   > 恢复正常

8. 编写四个服务对应的健康检查接口

   > 见controller/MiddlewareHealthController.java

9. 测试服务健康检查接口

   > mysql,mongodb,redis正常
   >
   > es报错{"timestamp":"2026-06-28T14:53:43.913Z","status":500,"error":"Internal Server Error","path":"/health/es"}

10. BUG：ES检查接口报错500 Internal Server Error

   > 1. 直接使用了indexOps.create()，重复创建已存在索引，第二次调用可能报错。
   > 2. 改用indices.exists()，但ES目前没有存储数据，返回空响应体可能导致报错。
   > 3. 使用httpclient直接调用接口，es正常

## 4. 结果：

> springboot后台使用Springboot启动失败，使用Application和WSL启动成功，暂时采用Application启动。
>
> （通过Springboot启动会注入额外运行参数，这里自动启用了 JMX Remote，但是JMX启动失败导致报错）
>
> 测试接口可以正常访问



# 六、阶段四

## 1. 目标：完成“上传文件 -> 保存 -> 解析 -> Chunk切分 -> 保存到Mongodb”

## 2. 操作：

## 3. 过程记录

1. 创建knowledge-ingestion模块，Type选择Maven, Configuration选择YAML（项目JDK26，java17）

2. 编写相关接口和文件。

3. 开始使用codex读取本地代码，分析BUG，将codex设置为只读模式，禁止直接修改本地文件。

4. BUG：运行时报“required a bean named 'mongoTemplate' that could not be found.”

   > 现在引的是 Spring Data MongoDB 库本身，但没有引 Spring Boot 的 MongoDB starter，所以 Spring Boot 没有完整启用 Mongo 自动配置，`MongoRepository` 找不到它依赖的 `mongoTemplate`。
   >
   > 
   >
   > 将
   >
   > ```
   > <dependency>
   >     <groupId>org.springframework.data</groupId>
   >     <artifactId>spring-data-mongodb</artifactId>
   > </dependency>
   > <dependency>
   >     <groupId>org.springframework</groupId>
   >     <artifactId>spring-web</artifactId>
   > </dependency>
   > ```
   >
   > 替换为
   >
   > ```
   > <dependency>
   >     <groupId>org.springframework.boot</groupId>
   >     <artifactId>spring-boot-starter-data-mongodb</artifactId>
   > </dependency>
   > 
   > <dependency>
   >     <groupId>org.springframework.boot</groupId>
   >     <artifactId>spring-boot-starter-web</artifactId>
   > </dependency>
   > ```
   >
   > 

   

5. BUG：运行时报8080接口被占用，切换8081、8082等多个接口均报被占用，且查询无占用。

   > 1. Windows / Hyper-V / WSL / Docker 有时会“保留”一批端口。被保留的端口不会出现在 `netstat` 里，但应用绑定时会报 “port already in use”。
   >
   > 2. 查询被保留的端口：`netsh interface ipv4 show excludedportrange protocol=tcp`
   >
   >    ```
   >    协议 tcp 端口排除范围
   >    
   >    开始端口    结束端口
   >    ----------    --------
   >            80          80
   >          1098        1197
   >          1198        1297
   >          1320        1419
   >          1496        1595
   >          5357        5357
   >          9293        9392
   >         14782       14881
   >         14882       14981
   >         27339       27339
   >         50000       50059
   >    ```
   >
   > 3. 更换未被占用的接口，可尝试18080等。
   >
   > 4. 模块成功启动。

6. BUG：maven compile报错：variable documentIngestionService not initialized in the default constructor

   > 1. 分析：Lombok 没有在编译时生效。
   >
   > 2. 处理：
   >
   >    删除@RequiredArgsConstructor，手动编写构造器
   >
   >    修改pom.xml
   >
   >    ```
   >    <dependency>
   >        <groupId>org.projectlombok</groupId>
   >        <artifactId>lombok</artifactId>
   >        <version>1.18.38</version>
   >        <scope>provided</scope>
   >    </dependency>
   >       
   >    <plugin>
   >        <groupId>org.apache.maven.plugins</groupId>
   >        <artifactId>maven-compiler-plugin</artifactId>
   >        <configuration>
   >            <annotationProcessorPaths>
   >                <path>
   >                    <groupId>org.projectlombok</groupId>
   >                    <artifactId>lombok</artifactId>
   >                    <version>1.18.38</version>
   >                </path>
   >            </annotationProcessorPaths>
   >        </configuration>
   >    </plugin>
   >    ```
   >
   > 3. 结果：maven compile成功。

7. 接口测试完毕，完成文件上传-切块-查询-删除流程。

## 4. 结果：完成文件上传-切块-查询-删除流程

# 七、阶段五

## 1. 目标：上传校验 + 异常统一返回 + Markdown Header Split

## 2. 操作：在knowledge-ingestion的common模块增加异常处理，上传部分增加文件校验，增加markdown切块器

## 3. 过程记录

1. 在 DocumentIngestionService.upload() 开头加文件空、文件大小、文件类型的校验。

2. 增加com.junhaohan.knowledgeingestion.common.GlobalExceptionHandler.java文件，封装报错信息

   ```
   package com.junhaohan.knowledgeingestion.common;
   
   import org.springframework.web.bind.annotation.ExceptionHandler;
   import org.springframework.web.bind.annotation.RestControllerAdvice;
   
   import java.util.Map;
   
   @RestControllerAdvice
   public class GlobalExceptionHandler {
   
       @ExceptionHandler(IllegalArgumentException.class)
       public Map<String, Object> handleIllegalArgumentException(IllegalArgumentException e) {
           return Map.of(
                   "success", false,
                   "message", e.getMessage()
           );
       }
   
       @ExceptionHandler(Exception.class)
       public Map<String, Object> handleException(Exception e) {
           return Map.of(
                   "success", false,
                   "message", "服务器内部错误"
           );
       }
   }
   ```

3. 新建service/splitter/MarkdownHeaderSplitter.java，处理markdown切块逻辑。

4. 在service/DocumentIngestionService.java增加切块选择策略。

5. 测试异常、不支持文件和markdown。

   ```
   curl -X POST http://localhost:8400/documents/upload -F "file=@test.md"
   curl -X POST http://localhost:8400/documents/upload -F "file=@test.pdf"
   curl -X DELETE http://localhost:8400/documents/6a474449c2e7c62138933a77
   curl http://localhost:8400/documents/6a474449c2e7c62138933a77
   curl http://localhost:8400/documents/6a474449c2e7c62138933a77/chunks
   curl http://localhost:8400/documents/ping
   ```

   测试完毕

6. BUG：数据被上传到mongodb的test数据库，而不是akp数据库

   > 原application.yml中的mongo配置为
   >
   > ```
   > spring:
   >   data:
   >     mongodb:
   >       host: localhost
   >       port: 27017
   >       database: akp
   > ```
   >
   > 但Spring Boot4.1.0，MongoDB 连接配置前缀已经是 `spring.mongodb.*`，不是 `spring.data.mongodb.*`。
   >
   > 修改为
   >
   > ```
   > spring:
   >   mongodb:
   >     host: localhost
   >     port: 27017
   >     database: akp
   > ```
   >
   > 成功上传到akp数据库

## 4. 结果

> 成功完成上传校验、异常处理和markdown切块



# 八、阶段六

1. 目标：Knowledge Ingestion 收尾增强。

   > 增加文档状态字段：PARSING，SUCCESS，FAILED
   >
   > 增加解析失败记录：errorMessage，updatedAt
   >
   > 增加重新解析接口：POST /documents/{id}/retry
   >
   > 增加 PDF / Word Parser 占位：PdfParser，WordParser

2. 操作：在upload接口中额外增加状态参数设置和失败原因记录，增加重新解析接口和pdf/word接口。

3. 过程记录：

   1. 增加文档状态字段和解析失败记录

      > 在DocumentIngestionService.java的upload函数中，document初始化时添加status为PARSING，document解析失败时更新status为FAILED并记录errormessage，解析完成后更新status为SUCCESS。
      >
      > 每次操作都更新时间参数updatedAt
      >
      > 在KnowledgeDocument.java即knowledge_document表对应的类中添加对应的status、errorMessage和updateAt参数。

   2. 增加重新解析接口

      > DocumentIngestionService.java增加retry接口
      >
      > DocumentController.java增加对外接口retry
      >
      > 测试：curl -X POST http://localhost:8080/documents/{documentId}/retry

   3. 增加 PDF / Word Parser 占位

      > 增加PdfParser.java和WordParser.java，仅编写入口，暂不提供能力。
      >
      > DocumentIngestionService.java中的validateFile函数，开放.pdf、.doc、.docx文件的校验。
      >
      > 注：不需要手动编写parser的跳转，upload中会自动列出所有parser，再逐个调用support函数检验是否符合。

4. 结果：

   > 顺利完成

# 本周决策

# 遗留问题

> milvus因端口占用问题删除了9091端口映射，主要为metrics / health。
>
> pdf、word解析入口已经编写，能力尚未开发。

# 下周计划

开发pdf、word解析能力。
