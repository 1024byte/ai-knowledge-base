"""
汇总报告生成脚本

读取所有评估报告（检索、生成、消融），生成一份完整的 Markdown 评估报告。

使用方式:
  python report.py
  python report.py --output reports/final_report.md
"""

import json
import os
import sys
import argparse
from datetime import datetime
from typing import Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config


def load_json(path: str) -> Optional[Dict]:
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return None


def build_report(retrieval: Optional[Dict], generation: Optional[Dict], ablation: Optional[list]) -> str:
    lines = []
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    lines.append("# RAG 系统评估报告")
    lines.append(f"\n> 生成时间: {now}")
    lines.append(f"> 测试集: `data/test_queries.json`")
    lines.append("")

    # ========== 1. 总览 ==========
    lines.append("---")
    lines.append("## 1. 总览")
    lines.append("")

    if retrieval:
        s = retrieval.get("summary", {})
        lines.append(f"| 项目 | 值 |")
        lines.append(f"|------|----|")
        lines.append(f"| 测试问题数 | {s.get('total_queries', '?')} |")
        lines.append(f"| 成功评估 | {s.get('evaluated', '?')} |")
        lines.append(f"| 跳过 | {s.get('skipped', '?')} |")
        lines.append(f"| 错误 | {s.get('errors', '?')} |")
        lines.append("")

    # ========== 2. 检索质量 ==========
    lines.append("---")
    lines.append("## 2. 检索质量")
    lines.append("")

    if retrieval:
        agg = retrieval.get("summary", {}).get("aggregated_metrics", {})
        k_values = retrieval.get("summary", {}).get("k_values", [3, 5, 10])

        lines.append("### 2.1 核心指标")
        lines.append("")
        lines.append("| 指标 | 分数 | 评级 | 说明 |")
        lines.append("|------|------|------|------|")

        metric_rows = [
            ("MRR", agg.get("mrr", 0), "第一个相关文档的排名倒数均值"),
        ]
        for k in k_values:
            metric_rows.append((f"Recall@{k}", agg.get(f"recall@{k}", 0), f"Top-{k} 中相关文档覆盖率"))
        for k in k_values:
            metric_rows.append((f"Precision@{k}", agg.get(f"precision@{k}", 0), f"Top-{k} 中相关文档占比"))
        for k in k_values:
            metric_rows.append((f"NDCG@{k}", agg.get(f"ndcg@{k}", 0), f"考虑排序位置的相关性得分"))
        for k in k_values:
            metric_rows.append((f"Hit Rate@{k}", agg.get(f"hit_rate@{k}", 0), f"至少命中 1 个的比例"))

        for name, score, desc in metric_rows:
            rating = _rating(score, name)
            lines.append(f"| {name} | {score:.4f} | {rating} | {desc} |")
        lines.append("")

        # 分层统计
        per_query = retrieval.get("per_query", [])
        if per_query:
            lines.append("### 2.2 按难度分层")
            lines.append("")
            lines.append("| 难度 | 数量 | Recall@5 | MRR |")
            lines.append("|------|------|----------|-----|")
            by_diff = {}
            for d in per_query:
                diff = d.get("difficulty", "unknown")
                if diff not in by_diff:
                    by_diff[diff] = []
                by_diff[diff].append(d)
            for diff in sorted(by_diff.keys()):
                items = by_diff[diff]
                avg_recall = sum(d["metrics"]["recall@5"] for d in items) / len(items)
                avg_mrr = sum(d["metrics"]["mrr"] for d in items) / len(items)
                lines.append(f"| {diff} | {len(items)} | {avg_recall:.4f} | {avg_mrr:.4f} |")
            lines.append("")

            lines.append("### 2.3 按类别分层")
            lines.append("")
            lines.append("| 类别 | 数量 | Recall@5 | MRR |")
            lines.append("|------|------|----------|-----|")
            by_cat = {}
            for d in per_query:
                cat = d.get("category", "unknown")
                if cat not in by_cat:
                    by_cat[cat] = []
                by_cat[cat].append(d)
            for cat in sorted(by_cat.keys(), key=lambda c: -len(by_cat.get(c, []))):
                items = by_cat[cat]
                avg_recall = sum(d["metrics"]["recall@5"] for d in items) / len(items)
                avg_mrr = sum(d["metrics"]["mrr"] for d in items) / len(items)
                lines.append(f"| {cat} | {len(items)} | {avg_recall:.4f} | {avg_mrr:.4f} |")
            lines.append("")

            # 失败案例
            failed = [d for d in per_query if d["metrics"].get("recall@5", 1) < 0.5]
            if failed:
                lines.append("### 2.4 低召回案例 (Recall@5 < 0.5)")
                lines.append("")
                for d in failed:
                    lines.append(f"- **{d['query_id']}**: {d['query'][:80]}... (Recall@5={d['metrics']['recall@5']:.2f})")
                lines.append("")

    # ========== 3. 生成质量 ==========
    lines.append("---")
    lines.append("## 3. 生成质量 (RAGAS)")
    lines.append("")

    if generation and "ragas_scores" in generation:
        ragas = generation["ragas_scores"]
        lines.append("| 指标 | 分数 | 评级 | 说明 |")
        lines.append("|------|------|------|------|")

        ragas_metrics = [
            ("faithfulness", "Faithfulness", "回答是否忠实于上下文，无编造"),
            ("answer_relevancy", "Answer Relevance", "回答是否切题"),
            ("context_precision", "Context Precision", "上下文是否精炼无冗余"),
            ("context_recall", "Context Recall", "上下文是否覆盖所有必要信息"),
        ]
        for key, label, desc in ragas_metrics:
            val = ragas.get(key)
            if val is not None:
                if isinstance(val, list):
                    avg = sum(v for v in val if v is not None) / max(1, len([v for v in val if v is not None]))
                    lines.append(f"| {label} | {avg:.4f} | {_rating(avg, key)} | {desc} |")
                else:
                    lines.append(f"| {label} | {val:.4f} | {_rating(val, key)} | {desc} |")
        lines.append("")
    elif generation:
        lines.append(f"> 共收集 {len(generation.get('samples', []))} 条样本，RAGAS 评估未执行")
        lines.append("> 请设置 `DEEPSEEK_API_KEY` 环境变量后运行 `python generation_eval.py`")
        lines.append("")

    # ========== 4. 消融实验 ==========
    lines.append("---")
    lines.append("## 4. 消融实验")
    lines.append("")

    if ablation:
        k_values = config.recall_k_values

        lines.append("### 4.1 各组指标对比")
        lines.append("")
        header = "| 实验 |"
        sep = "|------|"
        for k in k_values:
            header += f" Recall@{k} |"
            sep += "----------|"
        header += " MRR | NDCG@5 |"
        sep += "-----|--------|"
        lines.append(header)
        lines.append(sep)

        for report in ablation:
            label = report.get("label", report.get("experiment_id", "?"))
            agg = report.get("summary", {}).get("aggregated_metrics", {})
            row = f"| {label[:30]} |"
            for k in k_values:
                row += f" {agg.get(f'recall@{k}', 0):.4f} |"
            row += f" {agg.get('mrr', 0):.4f} |"
            row += f" {agg.get('ndcg@5', 0):.4f} |"
            lines.append(row)
        lines.append("")

        # 提升幅度
        baseline = None
        for report in ablation:
            if report.get("experiment_id") == "vector_only":
                baseline = report.get("summary", {}).get("aggregated_metrics", {})
                break

        if baseline:
            lines.append("### 4.2 提升幅度（vs 仅向量检索基线）")
            lines.append("")
            header = "| 实验 |"
            sep = "|------|"
            for k in k_values:
                header += f" ΔRecall@{k} |"
                sep += "-----------|"
            header += " ΔMRR | ΔNDCG@5 |"
            sep += "------|---------|"
            lines.append(header)
            lines.append(sep)

            for report in ablation:
                if report.get("experiment_id") == "vector_only":
                    continue
                label = report.get("label", "?")
                agg = report.get("summary", {}).get("aggregated_metrics", {})
                row = f"| {label[:30]} |"
                for k in k_values:
                    delta = agg.get(f"recall@{k}", 0) - baseline.get(f"recall@{k}", 0)
                    row += f" {delta:+.4f} |"
                delta_mrr = agg.get("mrr", 0) - baseline.get("mrr", 0)
                delta_ndcg = agg.get("ndcg@5", 0) - baseline.get("ndcg@5", 0)
                row += f" {delta_mrr:+.4f} |"
                row += f" {delta_ndcg:+.4f} |"
                lines.append(row)
            lines.append("")

    # ========== 5. 瓶颈分析 ==========
    lines.append("---")
    lines.append("## 5. 瓶颈分析与优化建议")
    lines.append("")

    tips = _analyze_bottlenecks(retrieval, generation, ablation)
    for tip in tips:
        lines.append(f"- {tip}")
    lines.append("")

    # ========== 6. 总结 ==========
    lines.append("---")
    lines.append("## 6. 总结")
    lines.append("")

    if retrieval:
        s = retrieval.get("summary", {}).get("aggregated_metrics", {})
        recall5 = s.get("recall@5", 0)
        mrr_val = s.get("mrr", 0)
        lines.append(f"当前系统在 {retrieval.get('summary', {}).get('evaluated', '?')} 条测试问题上的表现：")
        lines.append(f"- 检索召回率 Recall@5: **{recall5:.2%}**")
        lines.append(f"- 排序质量 MRR: **{mrr_val:.4f}**")
        lines.append("")
        if recall5 >= 0.8:
            lines.append("检索能力已达到良好水平，后续可重点关注生成质量优化。")
        elif recall5 >= 0.6:
            lines.append("检索能力基本达标，建议通过优化查询改写或调整混合检索权重来提升召回。")
        else:
            lines.append("检索能力有较大提升空间，建议优先排查文档切分质量和向量模型效果。")
        lines.append("")

    lines.append("---")
    lines.append(f"*报告由 `eval/report.py` 自动生成于 {now}*")

    return "\n".join(lines)


def _rating(score: float, metric_name: str) -> str:
    """根据指标名称和分数给出评级"""
    if "recall" in metric_name.lower() or "hit_rate" in metric_name.lower():
        if score >= 0.9:
            return "🟢 优秀"
        elif score >= 0.8:
            return "🟡 良好"
        elif score >= 0.6:
            return "🟠 及格"
        else:
            return "🔴 待改进"
    elif "precision" in metric_name.lower():
        if score >= 0.8:
            return "🟢 优秀"
        elif score >= 0.6:
            return "🟡 良好"
        else:
            return "🟠 待改进"
    elif "mrr" in metric_name.lower() or "ndcg" in metric_name.lower():
        if score >= 0.7:
            return "🟢 优秀"
        elif score >= 0.5:
            return "🟡 良好"
        else:
            return "🟠 待改进"
    elif "faithfulness" in metric_name.lower() or "answer_relevancy" in metric_name.lower():
        if score >= 0.85:
            return "🟢 优秀"
        elif score >= 0.7:
            return "🟡 良好"
        else:
            return "🟠 待改进"
    else:
        if score >= 0.8:
            return "🟢 优秀"
        elif score >= 0.6:
            return "🟡 良好"
        else:
            return "🟠 待改进"


def _analyze_bottlenecks(retrieval: Optional[Dict], generation: Optional[Dict], ablation: Optional[list]) -> list:
    tips = []

    # 检索瓶颈
    if retrieval:
        agg = retrieval.get("summary", {}).get("aggregated_metrics", {})
        recall5 = agg.get("recall@5", 0)
        mrr_val = agg.get("mrr", 0)
        precision5 = agg.get("precision@5", 0)

        if recall5 < 0.6:
            tips.append("**检索召回不足**：Recall@5 < 60%，建议检查文档切分质量（chunk 是否过大/过小）、向量模型是否匹配领域")
        if precision5 < 0.4:
            tips.append("**检索精度偏低**：Precision@5 < 40%，返回了很多无关内容，建议增强 Rerank 精排或调整混合检索权重")
        if mrr_val < 0.5:
            tips.append("**排序质量不佳**：MRR < 0.5，最相关的文档排名靠后，建议优化 Reranker 模型或增加候选池大小")

        # 分层分析
        per_query = retrieval.get("per_query", [])
        by_cat = {}
        for d in per_query:
            cat = d.get("category", "unknown")
            if cat not in by_cat:
                by_cat[cat] = []
            by_cat[cat].append(d)

        for cat, items in by_cat.items():
            if len(items) >= 2:
                avg_recall = sum(d["metrics"]["recall@5"] for d in items) / len(items)
                if avg_recall < 0.5:
                    tips.append(f"**类别 `{cat}` 召回偏低**：Recall@5={avg_recall:.2%}，该类别问题需要针对性优化")

    # 消融分析
    if ablation:
        baseline = None
        full = None
        for report in ablation:
            if report.get("experiment_id") == "vector_only":
                baseline = report.get("summary", {}).get("aggregated_metrics", {})
            elif report.get("experiment_id") == "full":
                full = report.get("summary", {}).get("aggregated_metrics", {})

        if baseline and full:
            delta = full.get("recall@5", 0) - baseline.get("recall@5", 0)
            if delta < 0.05:
                tips.append("**消融实验显示完整管线提升有限**：`full` vs `vector_only` 的 Recall@5 提升 < 5%，建议检查混合检索和 Rerank 是否真正发挥作用")
            elif delta < 0.1:
                tips.append("**完整管线有适度提升**：`full` vs `vector_only` 的 Recall@5 提升在 5%~10%，各组件基本有效，可进一步调优参数")

    # 生成瓶颈
    if generation and "ragas_scores" in generation:
        ragas = generation["ragas_scores"]
        faith = ragas.get("faithfulness", 1)
        if isinstance(faith, list):
            faith = sum(v for v in faith if v is not None) / max(1, len([v for v in faith if v is not None]))
        if faith < 0.7:
            tips.append("**忠实度偏低**：Faithfulness < 70%，LLM 可能编造了不在上下文中的内容，建议检查 System Prompt 是否强调'仅基于参考资料回答'")

    if not tips:
        tips.append("当前系统各项指标正常，暂无明显的瓶颈。建议持续扩充测试集以获得更稳定的评估结果。")

    return tips


def main():
    parser = argparse.ArgumentParser(description="生成 RAG 评估汇总报告")
    parser.add_argument("--output", default=os.path.join(config.reports_dir, "final_report.md"),
                        help="报告输出路径")
    args = parser.parse_args()

    print("生成汇总报告...")

    # 加载各报告
    retrieval = load_json(os.path.join(config.reports_dir, "retrieval_report.json"))
    generation = load_json(os.path.join(config.reports_dir, "generation_report.json"))
    ablation = load_json(os.path.join(config.reports_dir, "ablation_report.json"))

    found = sum(1 for r in [retrieval, generation, ablation] if r is not None)
    print(f"找到 {found}/3 份报告:")
    print(f"  检索评估: {'✓' if retrieval else '✗ (运行 retrieval_eval.py)'}")
    print(f"  生成评估: {'✓' if generation else '✗ (运行 generation_eval.py)'}")
    print(f"  消融实验: {'✓' if ablation else '✗ (运行 ablation.py)'}")

    if found == 0:
        print("\n未找到任何报告，请先运行评估脚本")
        return

    # 生成报告
    md = build_report(retrieval, generation, ablation)

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write(md)

    print(f"\n汇总报告已生成: {args.output}")


if __name__ == "__main__":
    main()