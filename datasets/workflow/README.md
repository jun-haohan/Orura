# 第八阶段 Workflow 验收

在仓库根目录运行 `scripts/workflow_eval.py`。脚本经真实 HTTP 接口创建临时流程定义和运行记录；结果保存到 `datasets/workflow/workflow_eval_results.json`，不覆盖 Agent / RAG 的评测结果。默认读取 `datasets/agent/cases.jsonl` 中 S08 的文档 ID、问题和参考 Chunk。若文档重新入库，请用 `--document-id` 覆盖旧 ID，并人工检查对应的新 Chunk。

## 环境与命令

后端及 MongoDB、检索服务、LLM 和原始语料已就绪时执行：

```bash
python scripts/workflow_eval.py --dry-run
python scripts/workflow_eval.py --ids T01 T02 T03 T04 T05 T06 T07 T08
python scripts/workflow_eval.py --ids T09 T10 T11 --document-id <实际文档ID>
python scripts/workflow_eval.py --document-id <实际文档ID>
```

只启动后端、MongoDB、工具注册表时，可运行 `python scripts/workflow_eval.py --skip-knowledge`；T09～T11 会被记录为 `SKIP`，不能算作完整验收。默认接口为 `http://127.0.0.1:18400`，可用 `--base-url`、`--timeout`、`--output` 调整。每次调用会创建新的 Workflow 定义和运行，不自动删除，便于查询轨迹。

| 场景 | 成功标准 |
| --- | --- |
| T01 | TOOL 算出 `9` 与 `4` 的绝对差 `5`，再次查询运行得到相同结果 |
| T02 | 缺少必填 `b`，START 返回 `INPUT_ERROR`，TOOL 未执行 |
| T03 | 文档范围没有交集，RAG 节点在调用检索前返回 `INPUT_ERROR` |
| T04 | 版本 2 创建后，指定版本 1 仍返回“版本一”；最新版本返回“版本二” |
| T05 | 多入边/循环定义在创建时被拒绝 |
| T06 | 未注册的独立 TOOL 在创建时被拒绝 |
| T07 | 引用不存在的上游节点在创建时被拒绝 |
| T08 | 知识答案与引用不来自同一节点，在创建时被拒绝 |
| T09 | S08 有证据问题经 `true` 分支返回限定文档的引用 |
| T10 | S08 文档不包含的火星问题经 `false` 分支返回无证据说明及空引用 |
| T11 | 有证据时 Agent 自行调用 `rag_ask`，并返回限定文档的引用 |

T09～T11 依赖当次语料、检索和模型行为。脚本核对状态、节点路径、引用中的文档和 Chunk ID；它不能证明引用正文与答案语义一致。请人工检查 T09、T11 报告中的 `citationChunkIds` 对应的 MongoDB 原文，特别核对 S08 的五类验证服务；若参考 Chunk ID 变化，以当次入库内容为准。T10 若检索到了无关 Chunk 而模型仍给出带引用的答案，应按真实失败记录，不要把它改写成通过。

超时由 `WorkflowExecutorTest.timesOutBlockingNode`、`timesOutWholeWorkflow` 和 `WorkflowBranchAndToolTest.mapsAgentTimeoutToWorkflowTimeout` 覆盖；遗留运行由 `WorkflowExecutorTest.recoversOrphanRunningRecord` 覆盖。这些需在可解析 Maven 依赖的环境运行：

```bash
cd backend/knowledge-ingestion
bash ./mvnw -Dtest=WorkflowExecutorTest,WorkflowBranchAndToolTest,WorkflowGraphValidatorTest,WorkflowDefinitionServiceTest test
```

现有归档 `datasets/agent/agent_eval_results.json` 的 `summary` 是 **15/20**，不是此前开发过程汇报的最终 **20/20**。若要声明 Workflow 未影响 Agent 和 RAG，请在同一环境重新运行 `python scripts/agent_eval.py` 及 `python scripts/rag_eval.py --cases datasets/rag/rag_qa_120.jsonl`，保留输出文件、代码提交和实际服务配置。不要用旧结果冒充这次回归。
