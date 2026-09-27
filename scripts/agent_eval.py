#!/usr/bin/env python3
"""运行 Agent 单步与两步中文题集，并保存逐题轨迹和汇总。"""

import argparse
import json
import re
import statistics
import sys
import time
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def normalized(value: str) -> str:
    """折叠全半角和空白，供答案与轨迹关键词核对。"""
    return "".join(unicodedata.normalize("NFKC", value).split()).casefold()


def contains_keyword(answer: str, keyword: str) -> bool:
    """匹配答案关键词，避免把负数或更大数字的末尾误认作目标值。"""
    content, target = normalized(answer), normalized(keyword)
    if target[0].isdigit():
        return re.search(rf"(?<![0-9.\-−负]){re.escape(target)}(?![0-9.])", content) is not None
    return target in content


def load_cases(path: Path) -> list[dict]:
    """读取题集，并在请求接口之前检查题目结构与标注。"""
    cases: list[dict] = []
    used_ids: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            case = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"第 {line_number} 行 JSON 无效：{exc.msg}") from exc
        if not isinstance(case, dict):
            raise ValueError(f"第 {line_number} 行不是 JSON 对象")
        cid = case.get("id")
        if not isinstance(cid, str) or not cid or cid in used_ids:
            raise ValueError(f"第 {line_number} 行 id 为空或重复")
        used_ids.add(cid)
        if case.get("type") not in ("single", "multi"):
            raise ValueError(f"{cid}: type 必须是 single 或 multi")
        if not isinstance(case.get("task"), str) or not case["task"].strip():
            raise ValueError(f"{cid}: task 不能为空")
        allowed = case.get("allowedTools")
        expected = case.get("expectedTools")
        if not isinstance(allowed, list) or not allowed or any(
            not isinstance(tool, str) or tool not in ("calculator", "absolute_difference", "rag_ask")
            for tool in allowed
        ) or len(allowed) != len(set(allowed)):
            raise ValueError(f"{cid}: allowedTools 无效")
        if not isinstance(expected, list) or not expected or any(
            not isinstance(tool, str) or tool not in allowed for tool in expected
        ):
            raise ValueError(f"{cid}: expectedTools 必须来自 allowedTools")
        if (case["type"] == "single" and len(expected) != 1) or (
            case["type"] == "multi" and len(expected) < 2
        ):
            raise ValueError(f"{cid}: 题型和预期工具数量不一致")
        steps = case.get("maxSteps")
        if type(steps) is not int or steps < len(expected) or steps > 4:
            raise ValueError(f"{cid}: maxSteps 不在有效范围内")
        status = case.get("expectedStatus")
        if status not in ("COMPLETED", "INSUFFICIENT_EVIDENCE"):
            raise ValueError(f"{cid}: expectedStatus 不合法")
        for field in ("answerKeywords", "stepKeywords"):
            if not isinstance(case.get(field), list):
                raise ValueError(f"{cid}: {field} 必须是数组")
        if any(not isinstance(word, str) or not word.strip()
               for word in case["answerKeywords"]):
            raise ValueError(f"{cid}: answerKeywords 含空值")
        if len(case["stepKeywords"]) != len(expected) or any(
            not isinstance(words, list) or any(
                not isinstance(word, str) or not word.strip() for word in words
            ) for words in case["stepKeywords"]
        ):
            raise ValueError(f"{cid}: stepKeywords 必须逐步填写")
        if type(case.get("expectCitations")) is not bool:
            raise ValueError(f"{cid}: expectCitations 必须是布尔值")
        doc_ids = case.get("documentIds")
        if doc_ids is not None and (
            not isinstance(doc_ids, list) or not doc_ids or any(
                not isinstance(doc, str) or not doc.strip() for doc in doc_ids
            )
        ):
            raise ValueError(f"{cid}: documentIds 无效")
        if "rag_ask" in expected and case["expectCitations"] != (status == "COMPLETED"):
            raise ValueError(f"{cid}: 有答案知识题须有引用；证据不足题不能有引用")
        refs = case.get("referenceChunkIds", [])
        if not isinstance(refs, list) or any(
            not isinstance(ref, str) or not ref.strip() for ref in refs
        ) or len(refs) != len(set(refs)):
            raise ValueError(f"{cid}: referenceChunkIds 必须是无重复字符串数组")
        if status == "INSUFFICIENT_EVIDENCE" and (refs or expected != ["rag_ask"]):
            raise ValueError(f"{cid}: 证据不足题须仅调用 rag_ask 且无参考引用")
        if status == "COMPLETED" and "rag_ask" in expected and not refs:
            raise ValueError(f"{cid}: 有答案知识题须标注参考 Chunk")
        calculation = case.get("calculation")
        numeric_tools = ("calculator", "absolute_difference")
        if sum(tool in numeric_tools for tool in expected) > 1:
            raise ValueError(f"{cid}: 每题只能标注一次数值计算工具")
        if any(tool in numeric_tools for tool in expected):
            if not isinstance(calculation, dict) or not isinstance(calculation.get("operands"), list) \
                    or not calculation["operands"] or not isinstance(calculation.get("result"), str):
                raise ValueError(f"{cid}: 计算题须标注 operands 和 result")
            if any(not isinstance(value, str) or not re.fullmatch(r"\d+(?:\.\d+)?", value)
                   for value in calculation["operands"] + [calculation["result"]]):
                raise ValueError(f"{cid}: calculation 必须使用无符号十进制数")
            if "absoluteDifference" in calculation:
                raise ValueError(f"{cid}: 绝对差请使用 absolute_difference 工具，不使用 calculation.absoluteDifference")
        elif calculation is not None:
            raise ValueError(f"{cid}: 无计算工具时不能标注 calculation")
        cases.append(case)
    if not cases:
        raise ValueError("题集为空")
    return cases


def post_json(url: str, payload: dict, timeout: float) -> dict:
    """调用已有 Agent HTTP 接口，并保留错误阶段信息。"""
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            body = json.load(response)
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail[:500]}") from exc
    except URLError as exc:
        raise RuntimeError(f"连接失败：{exc.reason}") from exc
    if not isinstance(body, dict):
        raise ValueError("Agent 接口未返回 JSON 对象")
    return body


def check_calculation(steps: list[dict], case: dict) -> bool:
    """核对计算工具真正使用的操作数和确定性计算结果。"""
    numeric_tools = ("calculator", "absolute_difference")
    indices = [index for index, tool in enumerate(case["expectedTools"]) if tool in numeric_tools]
    if not indices:
        return True
    index = indices[0]
    if index >= len(steps):
        return False
    summary = unicodedata.normalize("NFKC", str(steps[index].get("summary", "")))
    expression, separator, result = summary.rpartition("=")
    if not separator:
        return False
    expected = case["calculation"]
    try:
        actual_result = Decimal(result.strip())
        expected_result = Decimal(expected["result"])
        expected_operands = Counter(Decimal(value) for value in expected["operands"])
    except InvalidOperation:
        return False
    if actual_result != expected_result:
        return False
    if case["expectedTools"][index] == "absolute_difference":
        match = re.fullmatch(r"\s*([+-]?\d+(?:\.\d+)?)\s*与\s*"
                             r"([+-]?\d+(?:\.\d+)?)\s*的绝对差\s*", expression)
        if match is None:
            return False
        operands = [Decimal(value) for value in match.groups()]
        return Counter(operands) == expected_operands and abs(operands[0] - operands[1]) == actual_result
    actual_operands = Counter(Decimal(value) for value in re.findall(
        r"(?<![\d.])\d+(?:\.\d+)?(?![\d.])", expression))
    return actual_operands == expected_operands


def check_answer_magnitude(answer: str, case: dict) -> bool:
    """核对回答中的差值，避免把题目原值误当成最终计算结果。"""
    calculation = case.get("calculation")
    if calculation is None:
        return True
    amount = calculation["result"]
    content = normalized(answer).replace("−", "-")
    conclusions = []
    for match in re.finditer(
        r"(?:绝对差|相差|差值|差距|之差|计算结果|最终结果|结果|答案|回答|得到)"
        r"(?:为|是|等于|[:：=])?([+\-负]?\d+(?:\.\d+)?)(?![0-9.])", content
    ):
        if re.match(r"(?:[+*/-]\d+(?:\.\d+)?)+=", content[match.end(1):]):
            continue
        conclusions.append(Decimal(match.group(1).replace("负", "-")))
    if conclusions:
        return conclusions[-1] == Decimal(amount)
    return contains_keyword(answer, amount)


def build_task(case: dict) -> str:
    """返回题集定义的原始任务，不追加逐题修补提示。"""
    return case["task"].strip()


def evaluate_case(base_url: str, case: dict, document_id: str | None,
                  timeout: float) -> dict:
    """核对状态、工具次序、结果要点及每条引用的运行来源。"""
    result = {"id": case["id"], "type": case["type"],
              "sourceCaseId": case.get("sourceCaseId"), "passed": False}
    started = time.monotonic()
    try:
        doc_ids = case.get("documentIds")
        if doc_ids is not None:
            doc_ids = [document_id if doc == "${DOC_ID}" else doc for doc in doc_ids]
            if any(not doc for doc in doc_ids):
                raise ValueError("需要通过 --document-id 提供上传文档的 ID")
        request = {
            "task": build_task(case),
            "allowedTools": case["allowedTools"],
            "maxSteps": case["maxSteps"],
        }
        if doc_ids is not None:
            request["documentIds"] = doc_ids
        result["request"] = request
        response = post_json(base_url.rstrip("/") + "/api/agent/run", request, timeout)
        result["response"] = response
        steps = response.get("steps")
        citations = response.get("citations")
        answer = response.get("answer")
        if not isinstance(steps, list) or any(not isinstance(step, dict) for step in steps):
            raise ValueError("steps 不是对象数组")
        if not isinstance(citations, list) or any(
            not isinstance(citation, dict) for citation in citations
        ):
            raise ValueError("citations 不是对象数组")
        if not isinstance(answer, str):
            raise ValueError("answer 不是字符串")
        expected_tools = case["expectedTools"]
        refs = [item.get("ref") for item in citations]
        cited_chunks = {item.get("chunkId") for item in citations}
        expected_chunks = set(case.get("referenceChunkIds", []))
        actual_markers = {int(ref) for ref in re.findall(r"\[(\d+)]", answer)}
        valid_refs = all(type(ref) is int and ref > 0 for ref in refs)
        missing_answer = [word for word in case["answerKeywords"]
                          if not contains_keyword(answer, word)]
        step_checks = []
        missing_step = []
        for index, expected in enumerate(expected_tools):
            if index >= len(steps):
                break
            step = steps[index]
            missing = [word for word in case["stepKeywords"][index]
                       if normalized(word) not in normalized(str(step.get("summary", "")))]
            missing_step.append({"index": index + 1, "keywords": missing})
            step_checks.append(step.get("tool") == expected
                               and step.get("status") == "SUCCEEDED"
                               and step.get("index") == index + 1
                               and not missing)
        checks = {
            "status": response.get("status") == case["expectedStatus"],
            "steps": len(steps) == len(expected_tools) and all(step_checks),
            "answerKeywords": not missing_answer,
            "answerMagnitude": check_answer_magnitude(answer, case),
            "calculation": check_calculation(steps, case),
            "citationPresence": bool(citations) == case["expectCitations"],
            "citationMarkers": valid_refs and len(refs) == len(set(refs))
                               and actual_markers == set(refs),
            "citationSources": all(isinstance(c.get("documentId"), str)
                                   and c["documentId"] and isinstance(c.get("chunkId"), str)
                                   and c["chunkId"] for c in citations),
            "referenceCited": expected_chunks <= cited_chunks,
            "documentScope": doc_ids is None or all(
                item.get("documentId") in doc_ids for item in citations
            ),
        }
        result.update({
            "checks": checks,
            "missingAnswerKeywords": missing_answer,
            "missingReferenceChunkIds": sorted(expected_chunks - cited_chunks),
            "missingStepKeywords": missing_step,
            "failureReasons": [key for key, passed in checks.items() if not passed],
            "passed": all(checks.values()),
        })
    except (RuntimeError, ValueError, OSError) as exc:
        result["failureReasons"] = ["requestError"]
        result["error"] = str(exc)
    result["durationMs"] = round((time.monotonic() - started) * 1000)
    return result


def summarize(results: list[dict]) -> dict:
    """汇总任务成功、失败原因和端到端耗时。"""
    durations = sorted(item["durationMs"] for item in results)
    by_type = {
        kind: {"total": sum(item["type"] == kind for item in results),
               "passed": sum(item["type"] == kind and item["passed"] for item in results)}
        for kind in ("single", "multi")
    }
    return {
        "total": len(results),
        "passed": sum(item["passed"] for item in results),
        "failed": sum(not item["passed"] for item in results),
        "byType": by_type,
        "failureReasons": dict(Counter(reason for item in results
                                       for reason in item["failureReasons"])),
        "medianMs": round(statistics.median(durations)),
        "p95Ms": durations[(95 * len(durations) + 99) // 100 - 1],
    }


def main() -> int:
    """选择题目、执行评测，并保存可人工追查的 JSON 报告。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=Path("datasets/agent/cases.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("datasets/agent/agent_eval_results.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:18400")
    parser.add_argument("--document-id", help="上传固定 Markdown 语料后返回的 documentId")
    parser.add_argument("--model-name", default="Qwen/Qwen3-8B-AWQ")
    parser.add_argument("--timeout", type=float, default=210)
    parser.add_argument("--ids", nargs="*", help="只运行指定的题号，例如 S01 M01")
    parser.add_argument("--dry-run", action="store_true", help="只验证题集，不请求服务")
    parser.add_argument("--show-prompts", action="store_true", help="与 --dry-run 同用，显示实际发送的任务文本")
    args = parser.parse_args()
    if args.show_prompts and not args.dry_run:
        parser.error("--show-prompts 仅与 --dry-run 同用")
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    try:
        cases = load_cases(args.cases)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    if args.ids:
        unknown = set(args.ids) - {case["id"] for case in cases}
        if unknown:
            parser.error("未知题号：" + ", ".join(sorted(unknown)))
        cases = [case for case in cases if case["id"] in args.ids]
    if args.dry_run:
        print(f"题集有效：{len(cases)} 条；单步 {sum(c['type'] == 'single' for c in cases)} 条，"
              f"多步 {sum(c['type'] == 'multi' for c in cases)} 条")
        if args.show_prompts:
            for case in cases:
                print(f"{case['id']}: {build_task(case)}")
        return 0
    if any("${DOC_ID}" in case.get("documentIds", []) for case in cases) and not args.document_id:
        parser.error("知识题必须通过 --document-id 指定已入库文档")

    results = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.document_id, args.timeout)
        results.append(result)
        verdict = "PASS" if result["passed"] else "FAIL"
        print(f"{case['id']} [{case['type']}]: {verdict} "
              f"({result['durationMs']} ms) {','.join(result['failureReasons'])}")
    summary = summarize(results)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "declaredModel": args.model_name,
        "baseUrl": args.base_url,
        "caseFile": str(args.cases),
        "note": "关键词和轨迹检查只用于发现异常；事实是否受所引 Chunk 支持需人工复核。",
        "summary": summary,
        "cases": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"汇总：{summary}；详细结果：{args.output}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
