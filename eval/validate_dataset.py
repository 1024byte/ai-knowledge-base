"""
测试集校验工具

功能：
  1. 校验 JSON 格式是否正确
  2. 检查必填字段是否完整
  3. 统计问题分布（难度、类型、相关文档覆盖）
  4. 检查重复问题

使用方式：
  python validate_dataset.py
  python validate_dataset.py --file data/test_queries.json
"""

import json
import os
import sys
import argparse
from collections import Counter
from typing import List, Dict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config


REQUIRED_FIELDS = ["id", "query", "relevant_docs"]
OPTIONAL_FIELDS = ["difficulty", "category", "expected_answer"]


def load_test_queries(file_path: str) -> List[Dict]:
    with open(file_path, "r", encoding="utf-8") as f:
        return json.load(f)


def validate(queries: List[Dict]) -> Dict:
    report = {
        "total": len(queries),
        "errors": [],
        "warnings": [],
        "stats": {}
    }

    ids = []
    difficulties = []
    categories = []
    all_relevant_docs = []
    empty_queries = []
    empty_relevant_docs = []

    for i, q in enumerate(queries):
        for field in REQUIRED_FIELDS:
            if field not in q or q[field] is None:
                report["errors"].append(f"第{i}条: 缺少必填字段 '{field}'")
            elif isinstance(q[field], str) and not q[field].strip():
                report["errors"].append(f"第{i}条: 字段 '{field}' 为空")

        query = q.get("query", "")
        if query and ("待填写" in query or "【" in query):
            empty_queries.append(i)

        relevant_docs = q.get("relevant_docs", [])
        if not relevant_docs:
            empty_relevant_docs.append(i)
        else:
            all_relevant_docs.extend(relevant_docs)

        ids.append(q.get("id", ""))
        difficulties.append(q.get("difficulty", "unknown"))
        categories.append(q.get("category", "unknown"))

    if empty_queries:
        report["warnings"].append(f"有 {len(empty_queries)} 条问题未填写（索引: {empty_queries}）")
    if empty_relevant_docs:
        report["errors"].append(f"有 {len(empty_relevant_docs)} 条问题未标注相关文档（索引: {empty_relevant_docs}）")

    id_counts = Counter(ids)
    duplicates = [id_ for id_, count in id_counts.items() if count > 1]
    if duplicates:
        report["errors"].append(f"重复 ID: {duplicates}")

    report["stats"] = {
        "difficulty_distribution": dict(Counter(difficulties)),
        "category_distribution": dict(Counter(categories)),
        "relevant_doc_coverage": dict(Counter(all_relevant_docs)),
        "valid_count": report["total"] - len(empty_queries),
        "pending_count": len(empty_queries)
    }

    return report


def print_report(report: Dict):
    print("=" * 50)
    print("测试集校验报告")
    print("=" * 50)

    print(f"\n总条数: {report['total']}")
    print(f"有效条数: {report['stats']['valid_count']}")
    print(f"待填写: {report['stats']['pending_count']}")

    if report["errors"]:
        print(f"\n错误 ({len(report['errors'])}):")
        for err in report["errors"]:
            print(f"  - {err}")
    else:
        print("\n无错误")

    if report["warnings"]:
        print(f"\n警告 ({len(report['warnings'])}):")
        for warn in report["warnings"]:
            print(f"  - {warn}")

    print("\n难度分布:")
    for diff, count in sorted(report["stats"]["difficulty_distribution"].items()):
        print(f"  {diff}: {count}")

    print("\n类别分布:")
    for cat, count in sorted(report["stats"]["category_distribution"].items()):
        print(f"  {cat}: {count}")

    print("\n相关文档覆盖:")
    for doc, count in sorted(report["stats"]["relevant_doc_coverage"].items(),
                              key=lambda x: -x[1]):
        print(f"  {doc}: {count}")


def main():
    parser = argparse.ArgumentParser(description="校验 RAG 评估测试集")
    parser.add_argument("--file", default=config.test_queries_path, help="测试集路径")
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print(f"测试集文件不存在: {args.file}")
        print("请先运行 build_dataset.py 生成测试集")
        return

    queries = load_test_queries(args.file)
    report = validate(queries)
    print_report(report)

    if not report["errors"]:
        print("\n测试集校验通过，可以开始评估")
    else:
        print("\n测试集存在问题，请修复后重新校验")


if __name__ == "__main__":
    main()