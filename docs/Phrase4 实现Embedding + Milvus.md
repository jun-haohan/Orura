一、阶段目标

实现Embedding + Milvus，实现初步检索

二、当前环境

为了更加稳定，从openjdk 26.0.1修改为jdk 21.0.7

三、阶段一

1. 目标：Embedding 状态设计、MongoDB 数据模型调整、Milvus Collection Schema 定义。

2. 过程概览：

3. 详细步骤：
   1. 在KnowledgeDocument类中增加向量化相关字段embeddingsStatus、embeddingErrorMessage和embeddedAt。
   
   2. 安装milvus
   
      > 1. 报错，milvus启动失败：failed to bind host port for 0.0.0.0:9091:172.18.0.4:9091/tcp: address already in use
      >
      >    解决：windows保留了9015-9114端口，docker绑定9091端口失败，改为19091:9091
      >
      > 2. milvus容器频繁重启
      >
      >    分析：容器继承了代理服务HTTP_PROXY=http://127.0.0.1:7890，导致访问etcd时wwu误走代理路径，无法访问到容器内部的etcd
      >
      >    解决：给etcd、minio和milvus补上NO_PROXY/no_proxy
      >
      >    ```
      >    environment:
      >          NO_PROXY: localhost,127.0.0.1,0.0.0.0,::1,*.local,etcd,minio,milvus,akp-etcd,akp-minio,akp-milvus,172.18.0.0/16
      >          no_proxy: localhost,127.0.0.1,0.0.0.0,::1,*.local,etcd,minio,milvus,akp-etcd,akp-minio,akp-milvus,172.18.0.0/16
      >    ```
      >
      > 3. akp-elasticsearch容器频繁重启
      >
      >    分析：日志中有`AccessDeniedException: /usr/share/elasticsearch/data/node.lock`，确定为数据目录权限问题
      >
      >    解决：
      >
      >    ```
      >    chown -R 1000:0 /home/hhj/Orura/docker/data/elasticsearch
      >    chmod -R u+rwX,g+rwX /home/hhj/Orura/docker/data/elasticsearch
      >    docker compose up -d elasticsearch
      >    ```
      >
      >    
   
4. 结果：docker容器正常运行

四、阶段二

1. 目标：SpringBoot 接入 Milvus 2.5.4，并自动创建 knowledge_chunk_vector

2. 过程概览：

3. 详细步骤：

   1. 在pom.xml添加 Milvus Java SDK

      ```
      mvn dependency:get -Dartifact=io.milvus:milvus-sdk-java:2.5.14
      
      mvn clean compile
      
      Maven 面板
      → Reload All Maven Projects
      
      重启idea
      ```

   2. 在application.yml增加milvushhe和embedding的配置

   3. 创建配置类

      ```
      config/
      ├── MilvusProperties.java
      ├── EmbeddingProperties.java
      └── MilvusConfig.java
      ```

   4. 创建 Collection 初始化器，用于在springboot启动时保证Collection已经存在

      ```
      infrastructure/
      └── milvus/
          └── MilvusCollectionInitializer.java
      
      包含如下步骤：
      1. Collection存在检查
      2. 创建schema
      3. 创建HNSW索引
      4. 创建Collection
      ```

   5. 创建embedding相关配置和类

      ```
      - embedding
        - dto
          - ChunkVector
          - EmbeddingRequest
          - EmbeddingResponse
        - service
          - DocumentEmbeddingService
          - EmbeddingService
          - HttpEmbeddingService
      ```

   6. 创建python端的embedding-service

      ```
      - embedding-service
        - app
          - config.py
          - main.py
          - model.py
          - schemas.py
        - requirements.txt
      ```

   7. 启动embedding-service，测试接口

      ```
      uvicorn app.main:app --host 0.0.0.0 --port 19093
      
      curl http://localhost:19093/health
      
      curl -X POST http://localhost:19093/embed \
        -H "Content-Type: application/json" \
        -d '{
          "texts": [
            "人工智能正在快速发展",
            "Milvus是一个向量数据库"
          ]
        }'
      ```

      

4. 结果：java端embedding初始化程序成功启动，第一次启动时创建collection，第二次启动时未重复创建。python端embedding-service成功创建并验证

五、阶段三

1. 目标：java端与python端联调，打通milvus向量化能力

2. 过程概览：

3. 详细步骤：

   1. 检查java中application.yml的配置

      ```
      embedding:
        base-url: http://localhost:19093
        model: BAAI/bge-m3
        dimension: 1024
        batch-size: 16
      ```

   2. 编写手动向量化接口，暂时不打通自动化

      ```
      controller/
      └── DocumentEmbeddingController.java
      
      @PostMapping("/{documentId}/embedding")
      ```

   3. 向量化示例文档

   4. BUG：java端调用接口，python端返回报错：422 Unprocessable Content: "{"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}"

      > 1. 报错分析为python端收到的body为空
      >
      > 2. java端经过调试，request中的内容存在，大小正常，符合EmbeddingRequest类的属性，没有问题
      >
      > 3. python端经过测试，使用curl命令可以正常调用接口并向量化，及返回向量化结果
      >
      > 4. 从Spring的RestClient改为JDK原生HttpClient，依然报错
      >
      > 5. 根因：Java HttpClient 默认协议协商与 uvicorn/FastAPI 服务组合时，请求体没有被 Python 端正确读取。
      >
      >    Java HttpClient 默认可能尝试 HTTP/2 协议协商。在当前 uvicorn 服务环境下，这导致 POST 请求虽然到达了 Python 服务，headers 也存在，但 body 为空，FastAPI 因此返回 422 Unprocessable Content，提示 body 缺失。
      >
      > 6. 解决方案：在 Java 端固定使用 HTTP/1.1，避免默认协议协商
      >
      >    ```
      >    private final HttpClient httpClient = HttpClient.newBuilder()
      >            .version(HttpClient.Version.HTTP_1_1)
      >            .build();
      >    ```
      >
      >    

4. 结果：

六、阶段四

1. 目标：验证embedding自动化与retry能力

2. 过程概览：

3. 详细步骤：

   1. 修改代码，在upload后自动进行embedding

   2. 启动java服务与parser服务和embedding服务，上传文件，检查mongo中向量化状态

      ```
      curl -X POST http://localhost:18400/documents/upload -F "file=@ComplicatedWord.docx"
      向量化成功
      ```

      

   3. 关闭embedding服务，上传文件，检查向量化状态

      ```
      curl -X POST http://localhost:18400/documents/upload -F "file=@ComplicatedWord.docx"
      向量化失败
      ```

   4. 重启embedding服务，执行retry，检查执行后状态

      ```
      curl -X POST http://localhost:18400/api/documents/6aa307f4bb134d7ad43542b7/embedding/retry
      向量化成功
      ```

      

4. 结果：向量化流程验证完毕

七、阶段五

1. 目标：优化执行逻辑，避免项目异常关闭，以及重复触发问题

2. 过程概览：

3. 详细步骤：

   1. 项目重启后自动恢复正在向量化（PENDING）的任务

      > springboot启动后查询正在向量化的任务 PendingEmbeddingTaskRecovery

   2. 一致性检查，在向量化完成后校验chunks数量与milvus中的向量数量是否一致

      > 见/{documentId}/embedding/vectors接口

4. 结果：优化完毕

八、阶段六

1. 目标：实现语义检索接口

2. 过程概览

3. 详细步骤：

   1. 创建相关数据类

      ```
      retrieval/
      └── dto/
          └── VectorSearchHit.java   // Milvus 向量检索命中结果
          └── SemanticSearchResult.java
          └── SemanticSearchRequest.java
      ```

      

   2. MilvusVectorStore增加Topk搜索方法search()，根据向量执行topk搜索

   3. 创建SemanticSearchService，提供完整语义检索能力，可根据query返回topk最相关chunk

      ```
      retrieval/
      └── service/
          └── SemanticSearchService.java
      ```

   4. 创建 SemanticSearchController，提供知识库语义检索接口

   5. 测试接口http://localhost:18400/api/search/semantic，正常返回5个相关chunk

      

4. 结果：语义检索功能实现，测试接口正常

本周决策

遗留问题

下周计划