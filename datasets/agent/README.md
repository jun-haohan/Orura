# 第七阶段 Agent 评测

`datasets/rag/rag_qa_120.jsonl` 是原有 120 题 RAG 基线（原样复制），不是 120 道 Agent 任务。`cases.jsonl` 包含从其中标注的事实派生的 20 道 Agent 任务：10 道单步（8 道知识题、1 道证据不足题、1 道纯计算题）和 10 道 `rag_ask → calculator` 两步题。每道知识题通过 `sourceCaseId`、`sourceQuestion`、`referenceChunkIds` 对应原题；两步题另标注计算操作数和结果。

## 运行

在仓库根目录、已启动后端、LLM 和已建索引的原始文档库后运行：

```bash
python scripts/agent_eval.py --dry-run
python scripts/agent_eval.py --dry-run --show-prompts --ids S04 M06 M10
python scripts/agent_eval.py --ids S01 S09 S10 M01
python scripts/agent_eval.py --ids M06 M08 --output datasets/agent/agent_eval_m06_m08.json
python scripts/agent_eval.py
```

默认访问 `http://127.0.0.1:18400/api/agent/run`，逐题调用并写出 `datasets/agent/agent_eval_results.json`。Agent 单题和子集的评测报告也使用 `--output datasets/agent/<文件名>.json` 放入同一目录，避免覆盖全量结果。可用 `--base-url` 指定另一地址、`--timeout` 设置单题 HTTP 等待时间。原 RAG 题集单独评估：

```bash
python scripts/rag_eval.py --cases datasets/rag/rag_qa_120.jsonl
```

`referenceChunkIds`、文档过滤 ID 是原题库入库时的 ID；重新上传同一文件会产生不同的 ID，需要重新建立映射，不能直接把旧 ID 当成新语料的有效标注。完整 RAG 评测还需原始语料和索引；单凭此 JSONL 不包含文档正文。`S09` 的预期依赖当前语料确实不含该问题的证据。

报告分别检查状态、工具顺序、计算工具操作数和结果、答案关键词、引用编号及参考 Chunk 覆盖。M06、M08、M09 问的是两个目标数字的绝对差：评测脚本要求模型先找较大和较小的数字，再传给 `calculator` 做纯数字减法；不向计算器传 `abs()`、`max()`、单位等不支持的内容。评分仍容许计算器以相反顺序相减，但最终回答必须给出正差值。`--dry-run --show-prompts` 可核对请求的完整任务文本。S04 必须覆盖两个销量占比和两个乘用车能耗指标。M10 的 RAG 步骤及最终答案必须分别给出 98% 与 70%，最终差值为 28 个百分点。`steps` 不包含模型传入的工具参数；操作数从计算工具的公开摘要核验。关键词通过不等于事实正确，尤其多证据题仍需对照原始 Chunk 人工复核。RAG 和 Agent 评测应使用同一批已入库文档，才能比较上游证据与 Agent 决策的影响。

M10 若失败，先查看第一步 `rag_ask` 的 `steps[0].summary` 是否同时包含 98% 与 70%；公开摘要最多 160 字符，无法看清时直接用同样问题调用 `/api/rag/ask`。若 RAG 回答缺一项，应先排查知识工具或上游证据；若 RAG 答全而第二步仍用错数值，再排查 Agent 模型决策。
