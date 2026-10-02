#!/usr/bin/env python3
"""通过真实 Workflow HTTP 接口验收定义、运行、分支、引用与版本。"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


CASE_NAMES = {
    "T01": "独立 TOOL 计算及运行查询",
    "T02": "必填输入缺失",
    "T03": "文档范围不能扩大",
    "T04": "不可变版本与旧版运行",
    "T05": "非法循环",
    "T06": "未知工具",
    "T07": "非法上游绑定",
    "T08": "知识答案缺少同源引用",
    "T09": "有证据走 true 分支",
    "T10": "证据不足走 false 分支",
    "T11": "RAG → Agent 自取知识引用",
}
KNOWLEDGE_CASES = {"T09", "T10", "T11"}
DEFAULT_DATASET = Path(__file__).resolve().parents[1] / "datasets/agent/cases.jsonl"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "datasets/workflow/workflow_eval_results.json"


class ApiError(RuntimeError):
    """保留接口状态码和有限的错误正文。"""

    def __init__(self, status: int, body: str):
        """记录本次失败请求的状态和响应。"""
        try:
            parsed = json.loads(body)
            detail = parsed.get("message", body) if isinstance(parsed, dict) else body
        except json.JSONDecodeError:
            detail = body
        detail = str(detail)
        super().__init__(f"HTTP {status}: {detail[:300]}")
        self.status = status
        self.body = detail


class Client:
    """封装工作流 API 的 JSON 请求与响应。"""

    def __init__(self, base_url: str, timeout: float):
        """设置服务地址和单次请求上限。"""
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def call(self, method: str, path: str, payload: dict | None = None) -> dict:
        """请求真实服务，拒绝无效 JSON 或非对象响应。"""
        request = Request(
            self.base_url + path,
            data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method=method,
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                data = json.load(response)
        except HTTPError as error:
            raise ApiError(error.code, error.read().decode("utf-8", "replace")) from error
        except URLError as error:
            raise RuntimeError(f"接口连接失败: {error.reason}") from error
        if not isinstance(data, dict):
            raise ValueError("接口返回值不是 JSON 对象")
        return data

    def create(self, definition: dict) -> dict:
        """创建并读取流程定义的首个版本。"""
        return self.call("POST", "/api/workflows", definition)

    def run(self, workflow_id: str, input_data: dict, version: int | None = None) -> dict:
        """同步运行指定或最新版本。"""
        return self.call("POST", f"/api/workflows/{quote(workflow_id)}/runs",
                         {"version": version, "input": input_data})


def binding(*, path: str | None = None, value=None) -> dict:
    """构造只包含一个来源的字段绑定。"""
    return {"path": path} if path is not None else {"value": value}


def edge(source: str, target: str, branch: str | None = None) -> dict:
    """构造普通边或显式条件分支。"""
    item = {"from": source, "to": target}
    if branch is not None:
        item["branch"] = branch
    return item


def constant_definition(answer: str) -> dict:
    """构造无需外部依赖的两节点流程。"""
    return {"name": answer, "inputFields": {}, "nodes": [
        {"id": "start", "type": "START"},
        {"id": "finish", "type": "END", "outputBindings": {"answer": binding(value=answer)}},
    ], "edges": [edge("start", "finish")]}


def tool_definition() -> dict:
    """用显式数字输入调用已注册的绝对差工具。"""
    return {"name": "绝对差验收", "inputFields": {
        "a": {"type": "NUMBER", "required": True},
        "b": {"type": "NUMBER", "required": True}},
        "nodes": [{"id": "start", "type": "START"},
                  {"id": "calc", "type": "TOOL", "config": {"tool": "absolute_difference"},
                   "inputBindings": {"a": binding(path="input.a"),
                                     "b": binding(path="input.b")}},
                  {"id": "finish", "type": "END", "outputBindings": {
                      "answer": binding(path="nodes.calc.content")}}],
        "edges": [edge("start", "calc"), edge("calc", "finish")]}


def knowledge_definition(agent: bool = False) -> dict:
    """构造证据判断双分支，必要时接上 Agent 自取引用。"""
    nodes = [{"id": "start", "type": "START"},
             {"id": "search", "type": "RAG", "inputBindings": {
                 "question": binding(path="input.question")}},
             {"id": "check", "type": "CONDITION", "config": {
                 "left": "nodes.search.status", "op": "EQUALS", "right": "ANSWERED"}},
             {"id": "missing", "type": "END", "outputBindings": {
                 "answer": binding(value="当前文档没有足够依据。"),
                 "citations": binding(value=[])}}]
    edges = [edge("start", "search"), edge("search", "check"),
             edge("check", "missing", "false")]
    source = "search"
    if agent:
        nodes.append({"id": "agent", "type": "AGENT", "inputBindings": {
            "task": binding(value="请核对并列出中试平台提供的五类验证服务，附文档引用。"),
            "context": binding(path="nodes.search.answer")},
            "config": {"allowedTools": ["rag_ask"], "maxSteps": 1}})
        edges.append(edge("check", "agent", "true"))
        source = "agent"
    else:
        edges.append(edge("check", "answered", "true"))
    nodes.append({"id": "answered", "type": "END", "outputBindings": {
        "answer": binding(path=f"nodes.{source}.answer"),
        "citations": binding(path=f"nodes.{source}.citations")}})
    if agent:
        edges.append(edge("agent", "answered"))
    return {"name": "知识引用验收", "inputFields": {
        "question": {"type": "STRING", "required": True}},
        "nodes": nodes, "edges": edges}


def sample_document(dataset: Path) -> tuple[str, str, list[str]]:
    """从已经归档的 S08 题读取文档 ID、问题与参考片段。"""
    for line in dataset.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        case = json.loads(line)
        if case.get("id") == "S08":
            ids = case.get("documentIds", [])
            if len(ids) != 1 or not case.get("sourceQuestion"):
                raise ValueError("S08 缺少唯一文档 ID 或原问题")
            return ids[0], case["sourceQuestion"], case.get("referenceChunkIds", [])
    raise ValueError(f"未在 {dataset} 找到 S08")


def require(condition: bool, message: str) -> None:
    """给失败场景提供可阅读的具体断言信息。"""
    if not condition:
        raise AssertionError(message)


def reject(client: Client, definition: dict, keyword: str) -> dict:
    """确认非法定义在创建阶段被拒绝。"""
    try:
        client.create(definition)
    except ApiError as error:
        require(error.status == 400 and keyword in error.body,
                f"应返回 400 且包含 {keyword}，实际: {error}")
        return {"httpStatus": error.status, "error": error.body[:160]}
    raise AssertionError("非法定义被接受")


def assert_run(run: dict, status: str, node_ids: list[str]) -> None:
    """检查总体状态、节点顺序与非运行中终态。"""
    require(run.get("status") == status, f"预期状态 {status}，实际 {run.get('status')}")
    actual = [item.get("nodeId") for item in run.get("nodeRuns", [])]
    require(actual == node_ids, f"节点顺序预期 {node_ids}，实际 {actual}")
    require(run.get("endedAt") is not None, "缺少结束时间")


def evaluate(case_id: str, client: Client, document_id: str, question: str) -> dict:
    """运行一条固定场景，返回可存档的结构化观察值。"""
    if case_id == "T01":
        created = client.create(tool_definition())
        run = client.run(created["workflowId"], {"a": 9, "b": 4})
        assert_run(run, "SUCCEEDED", ["start", "calc", "finish"])
        require(run.get("answer", "").endswith("= 5"), "绝对差应等于 5")
        require(run.get("citations") == [], "纯计算不应带文档引用")
        saved = client.call("GET", f"/api/workflows/runs/{quote(run['id'])}")
        require(all(saved.get(key) == run.get(key) for key in
                    ("id", "workflowId", "version", "status", "answer", "citations"))
                and [item.get("nodeId") for item in saved.get("nodeRuns", [])]
                == ["start", "calc", "finish"],
                "按 runId 查询的核心结果与同步运行结果不一致")
        return {"runId": run["id"], "answer": run["answer"]}
    if case_id == "T02":
        created = client.create(tool_definition())
        run = client.run(created["workflowId"], {"a": 9})
        assert_run(run, "FAILED", ["start"])
        require(run.get("errorCode") == "INPUT_ERROR", "应在 START 拒绝缺失的 b")
        return {"runId": run["id"], "errorCode": run["errorCode"]}
    if case_id == "T03":
        definition = knowledge_definition()
        definition["nodes"][1]["config"] = {"documentIds": ["__outside_scope__"]}
        created = client.create(definition)
        run = client.run(created["workflowId"], {
            "question": "范围测试", "documentIds": ["__allowed_scope__"]})
        assert_run(run, "FAILED", ["start", "search"])
        require(run.get("errorCode") == "INPUT_ERROR", "文档范围无交集应在 RAG 前失败")
        return {"runId": run["id"], "errorCode": run["errorCode"]}
    if case_id == "T04":
        created = client.create(constant_definition("版本一"))
        workflow_id = created["workflowId"]
        latest = client.call("POST", f"/api/workflows/{quote(workflow_id)}/versions",
                             constant_definition("版本二"))
        old = client.call("GET", f"/api/workflows/{quote(workflow_id)}?version=1")
        current = client.call("GET", f"/api/workflows/{quote(workflow_id)}")
        first = client.run(workflow_id, {}, 1)
        second = client.run(workflow_id, {})
        require((created["version"], latest["version"], old["version"], current["version"])
                == (1, 2, 1, 2), "定义版本查询不一致")
        require((first["answer"], first["version"], second["answer"], second["version"])
                == ("版本一", 1, "版本二", 2), "新版本覆盖了旧版本运行")
        return {"workflowId": workflow_id, "oldRunId": first["id"], "newRunId": second["id"]}
    if case_id == "T05":
        definition = tool_definition()
        definition["edges"].append(edge("finish", "calc"))
        return reject(client, definition, "入边")
    if case_id == "T06":
        definition = tool_definition()
        definition["nodes"][1]["config"]["tool"] = "download_document"
        return reject(client, definition, "确定性工具")
    if case_id == "T07":
        definition = constant_definition("绑定错误")
        definition["nodes"][1]["outputBindings"]["answer"] = binding(path="nodes.unknown.answer")
        return reject(client, definition, "绑定")
    if case_id == "T08":
        definition = knowledge_definition()
        answered = next(node for node in definition["nodes"] if node["id"] == "answered")
        answered["outputBindings"]["citations"] = binding(value=[])
        return reject(client, definition, "同一节点的引用")
    if case_id in KNOWLEDGE_CASES:
        definition = knowledge_definition(agent=case_id == "T11")
        created = client.create(definition)
        asked = question if case_id != "T10" else (
            "这份中试平台建设指引是否明确记载 2099 年火星中试平台的五项产量？")
        run = client.run(created["workflowId"], {
            "question": asked, "documentIds": [document_id]})
        expected = (["start", "search", "check", "missing"] if case_id == "T10" else
                    ["start", "search", "check", "agent", "answered"] if case_id == "T11" else
                    ["start", "search", "check", "answered"])
        assert_run(run, "SUCCEEDED", expected)
        search = run["nodeRuns"][1]["output"]
        require(search.get("status") == ("INSUFFICIENT_EVIDENCE" if case_id == "T10" else "ANSWERED"),
                f"RAG 业务状态与预期不同: {search}")
        citations = run.get("citations", [])
        if case_id == "T10":
            require(not citations and run["answer"] == "当前文档没有足够依据。",
                    "证据不足分支不应生成引用")
        else:
            require(bool(citations) and all(item.get("documentId") == document_id
                                           and item.get("chunkId") for item in citations),
                    "知识引用缺失或超出文档范围")
            if case_id == "T11":
                steps = run["nodeRuns"][3]["output"].get("steps", [])
                require(any(step.get("tool") == "rag_ask" for step in steps),
                        "Agent 没有自行通过 rag_ask 取证")
        return {"runId": run["id"], "answer": run.get("answer"),
                "citationChunkIds": [item["chunkId"] for item in citations],
                "nodeIds": expected}
    raise ValueError(f"未知场景: {case_id}")


def main() -> int:
    """按顺序执行验收场景，并只把真实观察结果写入报告。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18400")
    parser.add_argument("--timeout", type=float, default=360.0)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--document-id", help="覆盖 S08 题集的文档 ID")
    parser.add_argument("--skip-knowledge", action="store_true", help="仅运行无需语料的场景")
    parser.add_argument("--ids", nargs="+", choices=CASE_NAMES, help="只运行指定场景")
    parser.add_argument("--dry-run", action="store_true", help="只展示验收计划")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    try:
        sample_id, question, references = sample_document(args.dataset)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(f"无法读取 S08 语料标注: {error}")
    document_id = args.document_id or sample_id
    ids = args.ids or list(CASE_NAMES)
    if args.dry_run:
        print(f"文档 ID: {document_id}；S08 参考 Chunk: {references}（仅供人工核对）")
        for case_id in ids:
            label = "跳过语料" if args.skip_knowledge and case_id in KNOWLEDGE_CASES else "待执行"
            print(f"{case_id} {CASE_NAMES[case_id]} [{label}]")
        return 0
    client = Client(args.base_url, args.timeout)
    results = []
    for case_id in ids:
        result = {"id": case_id, "name": CASE_NAMES[case_id]}
        if case_id in KNOWLEDGE_CASES and args.skip_knowledge:
            result.update(outcome="SKIP", detail="未启用语料验收")
        else:
            try:
                result.update(outcome="PASS", observed=evaluate(case_id, client, document_id, question))
            except (AssertionError, OSError, RuntimeError, KeyError, TypeError, ValueError) as error:
                result.update(outcome="FAIL", detail=str(error)[:500])
        results.append(result)
        print(f"{case_id} {result['outcome']}: {result.get('detail', CASE_NAMES[case_id])}")
    summary = {outcome: sum(item["outcome"] == outcome for item in results)
               for outcome in ("PASS", "FAIL", "SKIP")}
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(),
              "baseUrl": args.base_url, "dataset": str(args.dataset),
              "documentId": document_id, "referenceChunkIds": references,
              "summary": summary, "cases": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"报告: {args.output}；结果: {summary}")
    return 1 if summary["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
