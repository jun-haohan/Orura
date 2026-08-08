一、阶段目标

| 步骤 | 任务                        | 目标                            |      |      |      |
| ---- | --------------------------- | ------------------------------- | ---- | ---- | ---- |
| 1    | 新建 Python 解析服务        | 独立处理 PDF / Word             |      |      |      |
| 2    | 接入 Docling                | 优先解析 PDF / DOCX             |      |      |      |
| 3    | Java 调用 Python 服务       | SpringBoot 调用解析接口         |      |      |      |
| 4    | 替换 PdfParser / WordParser | 不再占位，调用真实解析          |      |      |      |
| 5    | 完善解析状态                | SUCCESS / FAILED / errorMessage |      |      |      |
| 6    | 测试复杂文档                | 表格、标题、代码块、长文档      |      |      |      |

二、当前环境



三、阶段一：新建python解析服务

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
      > `uvicorn app:app --host 0.0.0.0 --port 9002 --reload`
      >
      > 调用接口
      >
      > `curl http://localhost:9002/health`
      >
      > `curl -X POST http://localhost:9002/parse -F "file=@test.txt"`
      >
      > `curl -X POST http://localhost:9002/parse -F "file=@test.pdf"`
      >
      > `curl -X POST http://localhost:9002/parse -F "file=@test.jpg"`
      >
      > 测试正常

4. 结果：

   > python解析服务搭建完成，测试可正常解析txt、markdown、pdf、word文件，异常检查正常。



四、阶段二

1. 目标：JAVA端调用Python端的pdf/word解析能力

2. 操作：java端knowledge-ingestion服务中的 PdfParser / WordParser 调用Python端的parser-service提供的parse接口，将 pdf / word 解析为markdown文档。

3. 详细过程：

   1. JAVA端配置Python服务地址

      > knowledge-ingestion -  application.yml
      >
      > ```
      > parser:
      >   service:
      >     url: http://localhost:9002
      > ```
      >
      > 

   2. xxx

4. 结果：

五、阶段三

六、阶段四

七、阶段五：

本周决策

遗留问题

下周计划