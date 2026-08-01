"""
消融实验脚本

对比 4 组配置，量化每个组件的贡献：

  A: vector_only  → 仅向量检索（基线）
  B: no_rewrite   → 混合检索 + Rerank（跳过查询改写）
  C: no_rerank    → 查询改写 + 混合检索（跳过精排）
  D: full         → 查询改写 + 混合检索 + Rerank（完整管线）

使用方式:
  python ablation.py
  python ablation.py --file data/test_queries.json --output reports/ablation_report.json
"""

import json
import os
import sys
import argparse
import time
from typing import List, Dict
from collections import defaultdict

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config
from metrics import compute_all_metrics, aggregate_metrics
from retrieval_eval import match_relevant_docs, normalize_source


# 4 组实验配置
EXPERIMENTS = [
    {
        "id": "vector_only",
        "label": "A: 仅向量检索（基线）",
        "mode": "vector_only",
        "description": "纯向量语义检索，无查询改写、无BM25、无精排"
    },
    {
        "id": "no_rewrite",
        "label": "B: 混合检索 + Rerank（跳过查询改写）",
        "mode": "no_rewrite",
        "description": "混合检索 + Rerank，但不做查询改写"
    },
    {
        "id": "no_rerank",
        "label": "C: 查询改写 + 混合检索（跳过精排）",
        "mode": "no_rerank",
        "description": "查询改写 + 混合检索，但不做 Rerank 精排"
    },
    {
        "id": "full",
        "label": "D: 查询改写 + 混合检索 + Rerank（完整管线）",
        "mode": "full",
        "description": "当前完整管线"
    }
]


def load_test_queries(file_path: str) -> List[Dict]:
    with open(file_path, "r", encoding="utf-8") as f:
        return json.load(f)


def call_retrieve_api(query: str, mode: str) -> Dict:
    payload = {"query": query, "topK": config.top_k, "mode": mode}
    resp = requests.post(config.eval_retrieve_url, json=payload, timeout=30)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"API 返回错误: {body.get('message')}")
    return body["data"]


def run_experiment(exp: Dict, test_queries: List[Dict], k_values: List[int]) -> Dict:
    """运行单组实验"""
    mode = exp["mode"]
    label = exp["label"]
    print(f"\n{'=' * 60}")
    print(f"实验: {label}")
    print(f"模式: {mode}")
    print(f"{'=' * 60}")

    results = []
    per_query = []
    errors = []

    total = len(test_queries)
    for i, q in enumerate(test_queries):
        query_id = q.get("id", f"q{i}")
        query_text = q.get("query", "")
        relevant_docs = q.get("relevant_docs", [])

        if not query_text or not relevant_docs:
            continue

        try:
            api_response = call_retrieve_api(query_text, mode)
            final_ranked = api_response.get("finalRanked", [])
            retrieved_sources = [doc.get("source", "") for doc in final_ranked]
            relevant_set = set(normalize_source(r) for r in relevant_docs)
            matched = match_relevant_docs(retrieved_sources, relevant_docs)

            query_metrics = compute_all_metrics(relevant_set, retrieved_sources, k_values)
            query_metrics["query_id"] = query_id
            results.append(query_metrics)

            per_query.append({
                "query_id": query_id,
                "query": query_text,
                "difficulty": q.get("difficulty", "unknown"),
                "category": q.get("category", "unknown"),
                "metrics": {k: v for k, v in query_metrics.items() if k != "query_id"}
            })

            time.sleep(0.1)

        except Exception as e:
            errors.append({"id": query_id, "error": str(e)})

    aggregated = aggregate_metrics(results)

    print(f"  评估: {len(results)} 条, 错误: {len(errors)}")
    print(f"  Recall@5: {aggregated.get('recall@5', 0):.4f}")
    print(f"  MRR:      {aggregated.get('mrr', 0):.4f}")

    return {
        "experiment_id": exp["id"],
        "label": label,
        "mode": mode,
        "description": exp["description"],
        "summary": {
            "evaluated": len(results),
            "errors": len(errors),
            "aggregated_metrics": aggregated
        },
        "per_query": per_query,
        "errors": errors
    }


def print_comparison(reports: List[Dict]):
    """打印对比报告"""
    print("\n" + "=" * 80)
    print("消融实验对比报告")
    print("=" * 80)

    k_values = config.recall_k_values

    # 表头
    header = f"{'实验':<35}"
    for k in k_values:
        header += f" {'Recall@' + str(k):>10}"
    header += f" {'MRR':>10} {'NDCG@5':>10}"
    print(f"\n{header}")
    print("-" * 80)

    # 基线值（vector_only）
    baseline = None
    for report in reports:
        if report["experiment_id"] == "vector_only":
            baseline = report["summary"]["aggregated_metrics"]
            break

    # 每行数据
    for report in reports:
        label = report["label"]
        agg = report["summary"]["aggregated_metrics"]

        row = f"{label[:34]:<35}"
        for k in k_values:
            row += f" {agg.get(f'recall@{k}', 0):>10.4f}"
        row += f" {agg.get('mrr', 0):>10.4f}"
        row += f" {agg.get('ndcg@5', 0):>10.4f}"
        print(row)

    # 提升幅度
    if baseline:
        print(f"\n{'提升幅度（vs 基线）':-^80}")
        delta_header = f"{'实验':<35}"
        for k in k_values:
            delta_header += f" {'ΔRecall@' + str(k):>10}"
        delta_header += f" {'ΔMRR':>10} {'ΔNDCG@5':>10}"
        print(f"\n{delta_header}")
        print("-" * 80)

        for report in reports:
            if report["experiment_id"] == "vector_only":
                continue
            label = report["label"]
            agg = report["summary"]["aggregated_metrics"]

            row = f"{label[:34]:<35}"
            for k in k_values:
                delta = agg.get(f"recall@{k}", 0) - baseline.get(f"recall@{k}", 0)
                row += f" {delta:>+10.4f}"
            delta_mrr = agg.get("mrr", 0) - baseline.get("mrr", 0)
            delta_ndcg = agg.get("ndcg@5", 0) - baseline.get("ndcg@5", 0)
            row += f" {delta_mrr:>+10.4f}"
            row += f" {delta_ndcg:>+10.4f}"
            print(row)

    # 组件贡献分析
    print(f"\n{'组件贡献分析':-^80}")
    full = None
    no_rewrite = None
    no_rerank = None
    for report in reports:
        if report["experiment_id"] == "full":
            full = report["summary"]["aggregated_metrics"]
        elif report["experiment_id"] == "no_rewrite":
            no_rewrite = report["summary"]["aggregated_metrics"]
        elif report["experiment_id"] == "no_rerank":
            no_rerank = report["summary"]["aggregated_metrics"]

    if full and no_rewrite and no_rerank and baseline:
        print(f"\n各组件对 Recall@5 的贡献:")
        recall_key = "recall@5"
        base_recall = baseline.get(recall_key, 0)
        full_recall = full.get(recall_key, 0)
        no_rewrite_recall = no_rewrite.get(recall_key, 0)
        no_rerank_recall = no_rerank.get(recall_key, 0)

        print(f"  基线 (仅向量):           {base_recall:.4f}")
        print(f"  + 混合检索 (vs 基线):    {no_rerank_recall:.4f}  (Δ={no_rerank_recall - base_recall:+.4f})")
        print(f"  + 查询改写 (vs 混合):    {no_rewrite_recall:.4f}  (Δ={no_rewrite_recall - no_rerank_recall:+.4f})")
        print(f"  + Rerank 精排 (vs 改+混): {full_recall:.4f}  (Δ={full_recall - no_rewrite_recall:+.4f})")
        print(f"  完整管线:                {full_recall:.4f}  (总提升: Δ={full_recall - base_recall:+.4f})")


def main():
    parser = argparse.ArgumentParser(description="RAG 消融实验")
    parser.add_argument("--file", default=config.test_queries_path, help="测试集路径")
    parser.add_argument("--output", default=os.path.join(config.reports_dir, "ablation_report.json"),
                        help="报告输出路径")
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print(f"测试集不存在: {args.file}")
        return

    print("=" * 60)
    print("RAG 消融实验")
    print(f"API: {config.eval_retrieve_url}")
    print(f"测试集: {args.file}")
    print(f"实验组数: {len(EXPERIMENTS)}")
    print("=" * 60)

    test_queries = load_test_queries(args.file)
    print(f"\n加载 {len(test_queries)} 条测试问题")

    # 运行所有实验
    all_reports = []
    for exp in EXPERIMENTS:
        report = run_experiment(exp, test_queries, config.recall_k_values)
        all_reports.append(report)

    # 打印对比
    print_comparison(all_reports)

    # 保存报告
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(all_reports, f, ensure_ascii=False, indent=2)
    print(f"\n详细报告已保存至: {args.output}")


if __name__ == "__main__":
    main()