一、阶段目标：实现Agent Engine

二、当前环境

三、阶段一

1. 目标：建立数据协议、工具接口与配置
2. 过程概览：
3. 详细步骤：
   1. 在agent/model目录下新建Agent任务请求类AgentRequest，Agent任务返回类AgentResponse，工具调用结果AgentStep，Agent任务状态枚举AgentStatus，工具调用接口AgentAction，工具调用结果ToolResult
   2. 在runtime下新建Agent任务限制类AgentContext
   3. 在tool下新建Agent工具类AgentTool，ToolRegistry类选择可用工具
   4. 在config下新建Agent配置类AgentProperties
   5. 在application.yml下新增agent配置
4. 结果：

四、阶段二

1. 目标：实现两个工具
2. 过程概览：
3. 详细步骤：
   1. 在tool下新增RagAskTool类，用于调用RAG服务
   2. 在tool下新增CalculatorTool类，用于四则运算
4. 结果：

五、阶段三

1. 目标：打通单步 Agent 与接口
2. 过程概览：
3. 详细步骤：
   1. 在llm下新增AgentModelClient接口，将Agent对话转换为工具调用，新增HttpAgentModelClient类用于具体实现
   2. 在service下新增AgentService用于对接外部请求
   3. 在runtime下新增AgentExecutor用于执行工具调用
   4. 在controller下新增AgentController用于提供对外接口
4. 结果：

六、阶段四

1. 目标：扩展多步执行和运行约束
2. 过程概览：
3. 详细步骤：
   1. 在citation下新增CitationCollector用于统一不同RAG调用的编号
   2. 修改runtime/AgentExecutor，限制模型决策步数，限制工具调用次数，处理异常状态
   3. 修改llm/AgentModelClient，接收剩余时间
   4. 修改llm/HttpAgentModelClient，按剩余时间设置超时参数
   5. 修改service/AgentService，设置最大步数限制
   6. 修改controller/AgentController，更新接口注释
   7. 调用接口验收“RAG - Calculator”，调用正确，结果正确。
4. 结果：

七、阶段五

1. 目标：建立任务集并验收

2. 过程概览：

3. 详细步骤：

   1. 从上阶段生成的120道问答中，二次生成20道Agent任务，包含10条单步任务和10条多步任务。

   2. 经测试，20道任务中，15道题通过，1道单步任务报错，4道两步任务报错。

      > S04：引用到了chunk，计算没问题，但回答不全面
      >
      > M06: 引用到了Chunk，计算没问题，结果为-50，报计算错误，分析为没有取绝对值
      >
      > M08: 相同问题
      >
      > M09: 相同问题
      >
      > M10:引用到相关chunk, 但rag只查到一个数值, 导致第二步计算错误

   3. 通过调整prompt，明确需要回答的数值和计算的结果，S04和M10通过

   4. 通过新增绝对计算工具，使得可以指定返回绝对值，M06, M08, M09通过

4. 结果：

本周决策

遗留问题

下周计划