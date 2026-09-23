一、阶段目标：完成Retrieval Engine

1. 接入ES服务，封装ES操作服务
2. 跑通基于关键词的BM25检索，支持按条件过滤
3. 封装语义检索和关键词检索成统一Retriever，融合两种搜索结果，形成混合召回

二、当前环境

三、阶段一

1. 目标：接入ES，封装ES操作服务

2. 过程概览：

3. 详细步骤：

   1. 添加maven依赖

   2. 添加application.yml配置

   3. 测试ES功能正常

      ```
      curl http://localhost:19200
      curl http://localhost:19200/_cluster/health
      ```

   4. 创建ES knowledge_chunk的Index和Mapping

      ```
      {
        "settings": {
          "number_of_shards": 1,
          "number_of_replicas": 0
        },
        "mappings": {
          "properties": {
            "chunk_id": {
              "type": "keyword"
            },
            "document_id": {
              "type": "keyword"
            },
            "knowledge_base_id": {
              "type": "keyword"
            },
            "chunk_index": {
              "type": "integer"
            },
            "content": {
              "type": "text"
            }
          }
        }
      }
      ```

      

4. 结果：

四、阶段二

1. 目标：实现retrieval
2. 过程概览：
3. 详细步骤：
4. 结果：

五、阶段三

1. 目标：实现reranker
2. 过程概览：在 Hybrid 的 RRF 候选结果基础上，再用专门的重排模型计算 query + chunk 的相关性，最终得到 TopK
3. 详细步骤：
   1. embedding-service 增加 /rerank接口
   2. java定义RerankService，在RetrieveService的retrieve接口中，增加rerank部分。
   3. 补充es的删除函数，位于ElasticsearchSearchIndexService.java
   4. 完善删除文档逻辑，位于DocumentIngestionService.deleteDocument()，按顺序删除milvus、es、mongo和document。
4. 结果：

六、阶段四

1. 目标：实现Document Reindex
2. 过程概览：
3. 详细步骤：
   1. 创建ReindexService和ReindexServiceImpl，实现reindex()函数，先删除旧索引，再基于mongo chunk重新生成milvus和es索引
   2. 在DocumentController增加reindex接口
   3. 接口验证完毕
4. 结果：

七、阶段五

1. 目标：解析链路完成，清除测试数据，正式解析原始语料库

2. 过程概览：

3. 详细步骤：

   1. 编写SystemCleanupService和SystemCleanupController，由/clear-all接口负责清空测试数据，以便建立正式语料库

   2. 调用/clear-all，清空mongo、milvus和es内的测试数据

   3. 正式解析语料库

   4. BUG：解析报错：FAILED: Upload returned HTTP 413: 上传文件超过大小限制

      > 分析：初步发现报错的多为500kb以上文件，怀疑是有文件大小限制，检查后发现报错文件大小不一，成功的文件中也有2.13MB，因此不是文件大小限制
      >
      > 再次检查发现报错的文件均为长文件名，怀疑是文件名超过某处的长度限制，核对后发现Tomcat 对每个 multipart 分段头设置的 512 字节限制，按UTF-8编码为173个汉字。文件名本身在长度内，但存储时编码后的 Content-Disposition超过了长度限制。
      >
      > 解决：在application.yml中将  tomcat.max-part-header-size设置为2KB，足够满足当前需求

4. 结果：语料库建立完成，初始语料为100篇公开文档，格式为pdf

本周决策

遗留问题

下周计划