"""
检索质量评估脚本

调用 POST /api/eval/retrieve 获取完整检索链路结果，
计算 Recall@K、Precision@K、MRR、NDCG@K、Hit Rate 等指标。

使用方式:
  python retrieval_eval.py
  python retrieval_eval.py --file data/test_queries.json --output reports/retrieval_report.json
"""

import json
import os
import sys
import argparse
import time
from typing import List, Dict, Set
from collections import defaultdict

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config
from metrics import compute_all_metrics, aggregate_metrics


def load_test_queries(file_path: str) -> List[Dict]:
    with open(file_path, "r", encoding="utf-8") as f:
        return json.load(f)


def call_retrieve_api(query: str, top_k: int, session_id: str = None) -> Dict:
    """调用评估 API 获取检索结果"""
    payload = {"query": query, "topK": top_k}
    if session_id:
        payload["sessionId"] = session_id

    resp = requests.post(config.eval_retrieve_url, json=payload, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"API 返回错误: {body.get('message')}")
    return body["data"]


def normalize_source(source: str) -> str:
    """标准化 source 字符串，用于匹配"""
    if not source:
        return ""
    # 去掉路径前缀，只保留文件名
    return os.path.basename(source)


def match_relevant_docs(retrieved_sources: List[str], relevant_docs: List[str]) -> Set[str]:
    """
    匹配检索结果中的 source 与标注的相关文档。
    支持精确匹配和文件名匹配。
    """
    matched = set()
    normalized_retrieved = [normalize_source(s) for s in retrieved_sources]
    normalized_relevant = [normalize_source(r) for r in relevant_docs]

    for i, r_source in enumerate(normalized_retrieved):
        for rel in normalized_relevant:
            if r_source == rel or rel in r_source or r_source in rel:
                matched.add(r_source)
                break

    return matched


def evaluate_retrieval(test_queries: List[Dict], k_values: List[int]) -> Dict:
    """执行检索评估"""
    results = []
    per_query_details = []
    errors = []
    skipped = []

    total = len(test_queries)
    for idx, q in enumerate(test_queries):
        query_id = q.get("id", f"q{idx}")
        query_text = q.get("query", "")
        relevant_docs = q.get("relevant_docs", [])

        if not query_text or not relevant_docs:
            skipped.append({"id": query_id, "reason": "query 或 relevant_docs 为空"})
            continue

        print(f"[{idx + 1}/{total}] 评估: {query_text[:50]}...")

        try:
            api_response = call_retrieve_api(query_text, config.top_k)
            final_ranked = api_response.get("finalRanked", [])

            retrieved_sources = [doc.get("source", "") for doc in final_ranked]
            relevant_set = set(normalize_source(r) for r in relevant_docs)

            # 计算匹配
            matched = match_relevant_docs(retrieved_sources, relevant_docs)

            # 计算指标
            query_metrics = compute_all_metrics(relevant_set, retrieved_sources, k_values)
            query_metrics["query_id"] = query_id
            query_metrics["query"] = query_text
            results.append(query_metrics)

            # 详细信息
            per_query_details.append({
                "query_id": query_id,
                "query": query_text,
                "difficulty": q.get("difficulty", "unknown"),
                "category": q.get("category", "unknown"),
                "relevant_docs": relevant_docs,
                "retrieved_docs": [{"source": s, "score": d.get("score")}
                                   for s, d in zip(retrieved_sources, final_ranked)],
                "matched_docs": list(matched),
                "hit@5": list(matched & set(retrieved_sources[:5])),
                "metrics": {k: v for k, v in query_metrics.items()
                            if k not in ("query_id", "query")}
            })

            time.sleep(0.1)  # 避免请求过快

        except Exception as e:
            errors.append({"id": query_id, "error": str(e)})
            print(f"  错误: {e}")

    # 汇总
    aggregated = aggregate_metrics(results)

    return {
        "summary": {
            "total_queries": total,
            "evaluated": len(results),
            "skipped": len(skipped),
            "errors": len(errors),
            "k_values": k_values,
            "aggregated_metrics": aggregated
        },
        "per_query": per_query_details,
        "skipped": skipped,
        "errors": errors
    }


def print_report(report: Dict):
    s = report["summary"]
    agg = s["aggregated_metrics"]

    print("\n" + "=" * 60)
    print("检索评估报告")
    print("=" * 60)

    print(f"\n总查询数: {s['total_queries']}")
    print(f"成功评估: {s['evaluated']}")
    print(f"跳过: {s['skipped']}")
    print(f"错误: {s['errors']}")

    print(f"\n{'指标':<20} {'值':>10}")
    print("-" * 32)

    metric_display_order = [
        ("mrr", "MRR"),
    ]
    for k in s["k_values"]:
        metric_display_order.extend([
            (f"recall@{k}", f"Recall@{k}"),
            (f"precision@{k}", f"Precision@{k}"),
            (f"ndcg@{k}", f"NDCG@{k}"),
            (f"hit_rate@{k}", f"Hit Rate@{k}"),
        ])

    for key, label in metric_display_order:
        val = agg.get(key, 0)
        print(f"{label:<20} {val:>10.4f}")

    # 按难度分层
    by_difficulty = defaultdict(list)
    for detail in report["per_query"]:
        by_difficulty[detail.get("difficulty", "unknown")].append(detail)

    if len(by_difficulty) > 1:
        print(f"\n按难度分层:")
        print(f"{'难度':<10} {'数量':<8} {'Recall@5':>10} {'MRR':>10}")
        print("-" * 42)
        for diff in sorted(by_difficulty.keys()):
            items = by_difficulty[diff]
            avg_recall = sum(d["metrics"]["recall@5"] for d in items) / len(items)
            avg_mrr = sum(d["metrics"]["mrr"] for d in items) / len(items)
            print(f"{diff:<10} {len(items):<8} {avg_recall:>10.4f} {avg_mrr:>10.4f}")

    # 按类别分层
    by_category = defaultdict(list)
    for detail in report["per_query"]:
        by_category[detail.get("category", "unknown")].append(detail)

    if len(by_category) > 1:
        print(f"\n按类别分层:")
        print(f"{'类别':<12} {'数量':<8} {'Recall@5':>10} {'MRR':>10}")
        print("-" * 46)
        for cat in sorted(by_category.keys(), key=lambda c: -len(by_category[c])):
            items = by_category[cat]
            avg_recall = sum(d["metrics"]["recall@5"] for d in items) / len(items)
            avg_mrr = sum(d["metrics"]["mrr"] for d in items) / len(items)
            print(f"{cat:<12} {len(items):<8} {avg_recall:>10.4f} {avg_mrr:>10.4f}")


def main():
    parser = argparse.ArgumentParser(description="RAG 检索质量评估")
    parser.add_argument("--file", default=config.test_queries_path, help="测试集路径")
    parser.add_argument("--output", default=os.path.join(config.reports_dir, "retrieval_report.json"),
                        help="报告输出路径")
    parser.add_argument("--top-k", type=int, default=config.top_k, help="检索返回数量")
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print(f"测试集不存在: {args.file}")
        return

    config.top_k = args.top_k

    print("=" * 60)
    print("RAG 检索质量评估")
    print(f"API: {config.eval_retrieve_url}")
    print(f"测试集: {args.file}")
    print(f"Top-K: {config.top_k}")
    print("=" * 60)

    test_queries = load_test_queries(args.file)
    print(f"\n加载 {len(test_queries)} 条测试问题")

    report = evaluate_retrieval(test_queries, config.recall_k_values)

    print_report(report)

    # 保存报告
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n详细报告已保存至: {args.output}")

    # 检查是否有错误
    if report["summary"]["errors"] > 0:
        print(f"\n注意: {report['summary']['errors']} 条查询评估失败，请检查 API 是否正常运行")


if __name__ == "__main__":
    main()