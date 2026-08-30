# 一、阶段目标

| 步骤 | 任务                        | 目标                            |      |      |      |
| ---- | --------------------------- | ------------------------------- | ---- | ---- | ---- |
| 1    | 新建 Python 解析服务        | 独立处理 PDF / Word             |      |      |      |
| 2    | 接入 Docling                | 优先解析 PDF / DOCX             |      |      |      |
| 3    | Java 调用 Python 服务       | SpringBoot 调用解析接口         |      |      |      |
| 4    | 替换 PdfParser / WordParser | 不再占位，调用真实解析          |      |      |      |
| 5    | 完善解析状态                | SUCCESS / FAILED / errorMessage |      |      |      |
| 6    | 测试复杂文档                | 表格、标题、代码块、长文档      |      |      |      |

# 二、当前环境



# 三、阶段一：新建python解析服务

1. 目标：在python-service下搭建python解析服务，解析pdf/word文件

2. 过程：使用fastapi微服务，docling和mineru解析，将pdf/word文件解析为markdown格式。

3. 实际操作：

   1. 初始化目录结构

      > python-service/
      > └── parser-service/
      >     ├── app.py
      >     ├── requirements.txt
      >     └── uploads/

   2. 创建python虚拟环境，安装依赖

      > python3 --version
      > python3 -m venv .venv
      > source .venv/bin/activate

      > pip install -r requirements.txt

   3. BUG：pycharm无法添加WSL内的python解释器

      > Pycharm Pro付费解锁功能，本地为社区版

   4. 编写app.py

      > 读取文件
      >
      > 格式检查，非法格式报错
      >
      > 为文件生成随机uuid，保存在内存
      >
      > 使用docling解析pdf/word文档，转换为markdown格式
      >
      > 接口返回

   5. 启动测试

      > 在venv环境下，使用如下命令启动
      >
      > `uvicorn app:app --host 0.0.0.0 --port 19002 --reload`
      >
      > 调用接口
      >
      > `curl http://localhost:19002/health`
      >
      > `curl -X POST http://localhost:19002/parse -F "file=@test.txt"`
      >
      > `curl -X POST http://localhost:19002/parse -F "file=@test.pdf"`
      >
      > `curl -X POST http://localhost:19002/parse -F "file=@test.jpg"`
      >
      > 测试正常

4. 结果：

   > python解析服务搭建完成，测试可正常解析txt、markdown、pdf、word文件，异常检查正常。



# 四、阶段二：JAVA联通Python端解析能力

1. 目标：JAVA端调用Python端的pdf/word解析能力

2. 操作：java端knowledge-ingestion服务中的 PdfParser / WordParser 调用Python端的parser-service提供的parse接口，将 pdf / word 解析为markdown文档。

3. 详细过程：

   1. JAVA端配置Python服务地址

      > knowledge-ingestion -  application.yml
      >
      > ```
      > parser:
      >   service:
      >     url: http://localhost:19002
      > ```

   2. Java端搭建parse服务knowledgeingestion.client.ParserServiceClient，包装请求后调用python端提供的parse接口，读取返回结果。

   3. PdfParser和WordParser调用ParserServiceClient，实现Pdf/Word解析能力。

   4. 测试

      > `curl -X POST http://localhost:18400/documents/upload -F "file=@test.pdf"`
      >
      > 正常解析

4. 结果：

   > Java端成功调用python端解析能力，完成Pdf/Word解析。

# 五、阶段三：解析结果优化

1. 目标：为解析结果加上格式和解析器

2. 操作：解析结果增加"format"和"parser"属性，表示解析后的格式和使用的解析器，同时修改储存在mongo数据表

3. 详细过程

   1. 新建knowledgeingestion.service.parser.ParseResult返回体

      ```
      public record ParseResult (
              String content,
              String format,
              String parser
      ) {}
      ```

   2. 修改knowledgeingestion.domain.KnowledgeDocument，增加属性

      ```
      private String parser;
      
      private String contentFormat;
      
      private Integer contentLength;
      ```

      

   3. 逐个修改所有parser，将返回结果从String修改为ParseResult

      > PDF  -> format = markdown
      > DOCX -> format = markdown
      > MD   -> format = markdown
      > TXT  -> format = text

      > PDF      -> parser = docling
      > DOCX     -> parser = docling
      >
      > MD -> parser = builtin-markdown
      >
      > TXT      -> parser = builtin-text

   4. 修改knowledgeingestion.service.DocumentIngestionService的upload()和retry()函数，将直接获取解析后的字符串，改为获取ParseResult，再获取content等属性，并存入表中。

4. 结果

   > 成功修改

# 六、阶段四：Pdf/Word解析结果按章节再次切块

1. 目标：Pdf/Word解析结果可能过长，需要再过一次切块

2. 操作：Pdf/Word解析后，视作markdown文本，再进行一次长度切块

3. 详细过程
   1. 增加knowledgeingestion.service.splitter.MarkdownChunkSplitter，先将md文档按标题切分，再调用长度切分1000字以上的章节。

   2. 在knowledgeingestion.service.DocumentIngestionService中，将splitter选择逻辑修改为md文件使用新建splitter

   3. 修改MarkdownChunkSplitter.split()，在切分超长md章节之后，为每一个块都保留章节名

      ```
      // 调用长度切分器进一步切割，并为每一块都添加标题
      List<String> chunks = recursiveTextSplitter.split(section.content());
      for (String chunk : chunks) {
      	result.add(section.header() + "\n\n" + chunk);
      }
      ```

   4. 修改MarkdownHeaderSplitter，从返回`List<String>`改为返回`List<MarkdownSection>`，并优化解析逻辑：

      > 从前向后逐行扫描，记录当前标题并增加内容，遇到新标题时，保存上一个章节，并更新标题
      >
      > ```
      > if (!inCodeBlock && matcher.matches()) {
      > 	addSection(sections, currentHeader, content);
      > 	currentHeader = line.trim();
      > 	content.setLength(0);
      > } else {
      > 	content.append(line).append("\n");
      > }
      > ```
      >

   5. 修改MarkdownChunkSplitter，长度低于阈值的直接转Markdown，高于阈值的转RecrusiveTextSplitter进行切分，同时为每个chunk补上标题。

      ```
      if (markdown.length() <= MAX_CHUNK_SIZE) {
      	result.add(markdown);
      	continue;
      }
      
      List<String> chunks = recursiveSplitter.split(section.content());
      
      for (String chunk : chunks) {
      	result.add(withHeader(section.header(), chunk));
      }
      ```

   6. 将MarkdownChunkSplitter接入DocumentIngestionService，在split()函数中根据parseResult.format()选择对应的splitter。

4. 结果

   > 修改完成，接口测试正常

# 七、阶段五：解析质量全面测试

1. 目标：测试解析程序对于复杂样式Markdown和Pdf的解析效果

2. 操作：寻找多种不同样式的Markdown和Pdf文件，投入解析并分析结果。

3. 详细过程：

   1. 测试复杂Markdown，包括六级标题，空标题，长正文与短正文，代码块及其中的注释是否与标题混淆。

      > 解析正常

   2. 增加一个调试接口，查询该文档所有 Chunk，整理后写入当前运行目录的 tmp_output.txt，用于检查解析效果

   3. 调试功能无法启动

      > 报错：
      >
      > ```
      > ERROR: transport error 202: bind failed: Address already in use ERROR: JDWP Transport dt_socket failed to initialize, TRANSPORT_INIT(510) JDWP exit error AGENT_ERROR_TRANSPORT_INIT(197): No transports initialized [open/src/jdk.jdwp.agent/share/native/libjdwp/debugInit.c:697]
      > ```
      >
      > 原因分析：已有java进程，占用了调试接口
      >
      > 解决：
      >
      > ```
      > 查看当前java进程(第二项为pid)
      > ps -ef | grep java
      > 结束进程
      > kill -9 [pid]
      > ```

   4. 测试复杂Pdf

      > 问题：上传ComplicatedPdf.pdf时报错，经检查没能进入upload函数，原因为Spring Boot默认multipart通常有大小限制，1MB
      >
      > 解决：修改application.yml，增大文件上传限制
      >
      > ```
      > spring:
      >   servlet:
      >     multipart:
      >       max-file-size: 100MB
      >       max-request-size: 100MB
      > ```
      >
      > 解析复杂Pdf成功。

   5. 测试复杂word

      > 解析正常

4. 结果

   解析质量测试完成

# 本周决策

# 遗留问题

markdown切分时只能按每一个标题切，无法保留标题层级树

异常场景处理不足：

> 不支持格式
> 空文件
> 超大文件
> 损坏 PDF
> 损坏 DOCX
> Python parser-service 不可用
> Docling 解析异常
> Retry 是否恢复正常

# 下周计划

Embedding + Milvus / Retrieval Engine，将解析切片转换为可检索的向量，并完成初步检索。



