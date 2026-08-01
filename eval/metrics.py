"""
检索评估指标计算模块

实现的指标:
  - Recall@K:     Top-K 结果中命中相关文档的比例
  - Precision@K:  Top-K 结果中相关文档的比例
  - MRR:          第一个相关文档排名的倒数均值
  - NDCG@K:       归一化折损累计增益
  - Hit Rate@K:   至少命中 1 个相关文档的查询比例
"""

import math
from typing import List, Set


def recall_at_k(relevant: Set[str], retrieved: List[str], k: int) -> float:
    """Recall@K: Top-K 中命中的相关文档数 / 总相关文档数"""
    if not relevant:
        return 0.0
    retrieved_k = set(retrieved[:k])
    return len(relevant & retrieved_k) / len(relevant)


def precision_at_k(relevant: Set[str], retrieved: List[str], k: int) -> float:
    """Precision@K: Top-K 中相关文档数 / K"""
    if k == 0:
        return 0.0
    retrieved_k = set(retrieved[:k])
    return len(relevant & retrieved_k) / k


def mrr(relevant: Set[str], retrieved: List[str]) -> float:
    """MRR: 第一个相关文档排名的倒数"""
    for i, doc in enumerate(retrieved):
        if doc in relevant:
            return 1.0 / (i + 1)
    return 0.0


def dcg_at_k(relevant: Set[str], retrieved: List[str], k: int) -> float:
    """DCG@K: 折损累计增益（每个相关文档只计一次，避免重复文档导致 NDCG > 1.0）"""
    dcg = 0.0
    seen = set()
    for i, doc in enumerate(retrieved[:k]):
        if doc in relevant and doc not in seen:
            seen.add(doc)
            dcg += 1.0 / math.log2(i + 2)
    return dcg


def ndcg_at_k(relevant: Set[str], retrieved: List[str], k: int) -> float:
    """NDCG@K: 归一化折损累计增益"""
    dcg = dcg_at_k(relevant, retrieved, k)
    # 理想 DCG：所有相关文档都排在最前面
    ideal_relevant_count = min(len(relevant), k)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_relevant_count))
    if idcg == 0:
        return 0.0
    return dcg / idcg


def hit_rate_at_k(relevant: Set[str], retrieved: List[str], k: int) -> bool:
    """Hit Rate@K: Top-K 中是否至少命中 1 个相关文档"""
    retrieved_k = set(retrieved[:k])
    return bool(relevant & retrieved_k)


def compute_all_metrics(relevant: Set[str], retrieved: List[str], k_values: List[int]) -> dict:
    """计算单个查询的所有指标"""
    metrics = {}
    metrics["mrr"] = mrr(relevant, retrieved)
    for k in k_values:
        metrics[f"recall@{k}"] = recall_at_k(relevant, retrieved, k)
        metrics[f"precision@{k}"] = precision_at_k(relevant, retrieved, k)
        metrics[f"ndcg@{k}"] = ndcg_at_k(relevant, retrieved, k)
        metrics[f"hit_rate@{k}"] = hit_rate_at_k(relevant, retrieved, k)
    return metrics


def aggregate_metrics(all_results: List[dict]) -> dict:
    """汇总所有查询的指标，返回均值"""
    if not all_results:
        return {}

    aggregated = {}
    # 收集所有指标名称
    metric_keys = [k for k in all_results[0].keys() if k not in ("query_id", "query", "retrieved", "relevant")]
    for key in metric_keys:
        values = [r[key] for r in all_results if key in r]
        aggregated[key] = sum(values) / len(values) if values else 0.0

    # hit_rate 已经是均值
    hit_keys = [k for k in metric_keys if "hit_rate" in k]
    for k in hit_keys:
        values = [r[k] for r in all_results if k in r]
        aggregated[k] = sum(values) / len(values) if values else 0.0

    return aggregated