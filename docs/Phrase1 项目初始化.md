- 项目初始化，创建github仓库
- 准备IDE环境，打通SSH与GIt
- 初始化springboot后台



1. 创建github仓库

2. clone到wsl子系统中，手动创建Monorepo目录框架。

3. 创建记录文件，每日更新。

4. 下载idea，创建父工程backend，组名称使用昵称，项目配置选择maven，jar，yaml。

   > 多模块springboot项目，初始化时只有src部分，而常见多模块结构为common+其他模块。因此，新建admin-server模块并将src剪切过去，新建common模块，在父pom中添加modules项。
   >
   
5. 创建common模块和admin-server模块，将src模块移动到admin-server内作为启动入口，在父pom添加packaging为pom。

6. 更新本地jdk为指定版本，更新JAVA_HOME

7. mvn clean install顺利通过。



# Bug记录

1. mvn clean报错'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging. @ line 3, column 110

   > 父pom需要配置packaging为pom

2. mvn install报错Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile (default-compile) on project common: Fatal error compiling: error: release version 21 not supported -> [Help 1]

   > 配置中jdk版本为21，但当前maven使用的低于21，更新jdk即可，同时检查JAVA_HOME是否指向jdk21。



# 详细步骤

## 基础框架

1. 创建github仓库，勾选readme，.gitignore(Java)和LICENSE(MIT)

2. clone到WSL内，手动创建Monorepo结构

   > ```
   > ├── backend
   > │    ├── knowledge-ingestion
   > │    ├── knowledge-governance
   > │    ├── retrieval-engine
   > │    ├── agent-engine
   > │    ├── workflow-engine
   > │    ├── mcp-client
   > │    ├── admin-platform
   > │    └── common
   > │
   > ├── python-service
   > │    ├── embedding-service
   > │    ├── reranker-service
   > │    └── llm-service
   > │
   > ├── docker
   > │    ├── docker-compose.yml
   > │    └── data/
   > │        ├── mysql/
   > │        ├── mongodb/
   > │        ├── redis/
   > │        ├── milvus/
   > │        └── elasticsearch/
   > │
   > ├── docs
   > │
   > ├── datasets
   > │
   > └── scripts
   > ```

3. 配置wsl到github的ssh_key

   > 复制PC的ssh_key到wsl的指定位置
   >
   > 也可以为wsl创建一个独立key
   >
   > ```
   > ssh-keygen -t ed25519
   > 
   > cat ~/.ssh/id_ed25519.pub
   > ```

## Java后台创建

在backend文件夹新建springboot项目,使用maven，jar，yaml