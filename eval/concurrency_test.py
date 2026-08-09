"""
RAG 系统并发性能测试

测试 POST /api/chat/ask 在不同并发数下的表现，
输出延迟分布、吞吐量、成功率。

使用方式:
  python concurrency_test.py                     # 默认并发 1,3,5,10
  python concurrency_test.py -c 5                # 仅测试并发 5
  python concurrency_test.py -c 1,3,5,10,20      # 自定义多组并发
  python concurrency_test.py -q custom.json      # 自定义问题集
"""

import os
import sys
import json
import time
import argparse
import statistics
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import config

# ============================================================
# 测试问题池（混合简单和复杂查询，覆盖不同耗时场景）
# ============================================================
TEST_QUESTIONS = [
    "根据讲义第一篇文章第一段，大多数美国人一生换职业的次数是多少？",
    "美国所有员工都在思考什么问题？",
    "线上网站职业测试的作用是什么？",
    "大多数学生上大学的核心目标是什么？",
    "阿根廷闻名世界的原因是什么？",
    "运动员只追求胜利会产生什么后果？",
    "从什么时候开始发行冬奥纪念邮票成为固定惯例？",
    "结合第一篇和第二篇阅读，分别梳理美国人频繁跳槽、大学生忽视学习的共同内在诱因是什么？",
]


def call_chat_api(question: str, session_prefix: str) -> dict:
    """单次请求，返回耗时和结果"""
    payload = {
        "question": question,
        "sessionId": f"{session_prefix}-{question[:10]}",
        "topK": 5
    }
    start = time.time()
    try:
        resp = requests.post(config.chat_url, json=payload, timeout=120)
        elapsed = time.time() - start
        body = resp.json()
        return {
            "question": question[:40],
            "status": resp.status_code,
            "code": body.get("code"),
            "time_ms": round(elapsed * 1000),
            "answer_len": len(body.get("data", {}).get("answer", "")),
            "error": None
        }
    except Exception as e:
        elapsed = time.time() - start
        return {
            "question": question[:40],
            "status": -1,
            "code": None,
            "time_ms": round(elapsed * 1000),
            "answer_len": 0,
            "error": str(e)[:100]
        }


def run_concurrency_test(concurrency: int, questions: list) -> dict:
    """以指定并发数执行测试"""
    print(f"\n{'=' * 60}")
    print(f"  并发数: {concurrency}")
    print(f"{'=' * 60}")

    tasks = []
    for i in range(concurrency):
        q = questions[i % len(questions)]
        tasks.append((q, f"conc-{concurrency}-{i}"))

    results = []
    batch_start = time.time()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(call_chat_api, q, sid): q for q, sid in tasks}
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            status = "OK" if result["status"] == 200 and result["code"] == 0 else "FAIL"
            print(f"  [{status}] {result['question']}... {result['time_ms']}ms")

    total_time = time.time() - batch_start
    times = [r["time_ms"] for r in results]
    success = [r for r in results if r["status"] == 200 and r["code"] == 0]

    return {
        "concurrency": concurrency,
        "total_requests": len(results),
        "success": len(success),
        "failed": len(results) - len(success),
        "total_time_ms": round(total_time * 1000),
        "min_ms": round(min(times)),
        "max_ms": round(max(times)),
        "avg_ms": round(statistics.mean(times)),
        "p50_ms": round(statistics.median(times)),
        "p95_ms": round(sorted(times)[int(len(times) * 0.95)]),
        "p99_ms": round(sorted(times)[int(len(times) * 0.99)]),
        "throughput_qps": round(len(results) / total_time, 2),
        "errors": list(set(r["error"] for r in results if r["error"]))
    }


def print_report(all_results: list):
    print("\n" + "=" * 75)
    print("  并发测试报告")
    print("=" * 75)

    header = (f"{'并发':<6} {'成功':<6} {'失败':<6} {'平均ms':<10} "
              f"{'P50ms':<10} {'P95ms':<10} {'P99ms':<10} {'QPS':<8}")
    print(header)
    print("-" * 75)

    for r in all_results:
        print(f"{r['concurrency']:<6} {r['success']:<6} {r['failed']:<6} "
              f"{r['avg_ms']:<10} {r['p50_ms']:<10} {r['p95_ms']:<10} "
              f"{r['p99_ms']:<10} {r['throughput_qps']:<8}")

    # 瓶颈分析
    print("\n  瓶颈分析:")
    for r in all_results:
        if r["failed"] > 0:
            print(f"    并发 {r['concurrency']}: {r['failed']} 个请求失败")
            for e in r["errors"]:
                if e:
                    print(f"      └─ {e}")

    # 线性度判断
    if len(all_results) >= 2:
        base = all_results[0]
        last = all_results[-1]
        if base["avg_ms"] > 0:
            ratio = last["avg_ms"] / base["avg_ms"]
            print(f"\n  并发放大比: {last['concurrency']}并发 / 1并发 = {ratio:.1f}x 耗时")
            if ratio > last["concurrency"] * 0.8:
                print("  ⚠️ 近线性退化，Ollama 或 Reranker 是瓶颈")
            elif ratio > last["concurrency"] * 0.5:
                print("  🟡 亚线性退化，存在一定排队但可接受")
            else:
                print("  ✅ 并发扩展良好")

    # 稳定性
    print("\n  稳定性:")
    for r in all_results:
        jitter = r["p99_ms"] - r["p50_ms"]
        print(f"    并发 {r['concurrency']}: P99-P50 抖动 = {jitter}ms", end="")
        if jitter > r["p50_ms"] * 2:
            print(" ⚠️ 长尾严重")
        else:
            print(" ✅")


def main():
    parser = argparse.ArgumentParser(description="RAG 系统并发性能测试")
    parser.add_argument("-c", "--concurrency", default="1,3,5,10",
                        help="并发数，逗号分隔（默认: 1,3,5,10）")
    parser.add_argument("-q", "--questions", default=None,
                        help="自定义问题文件（JSON 字符串数组）")
    parser.add_argument("-o", "--output", default="reports/concurrency_report.json",
                        help="报告输出路径")
    parser.add_argument("--warmup", action="store_true",
                        help="正式测试前先发一条请求做预热")
    args = parser.parse_args()

    concurrency_levels = [int(x.strip()) for x in args.concurrency.split(",")]

    if args.questions:
        with open(args.questions, "r", encoding="utf-8") as f:
            questions = json.load(f)
    else:
        questions = TEST_QUESTIONS

    print(f"测试目标: {config.chat_url}")
    print(f"测试问题池: {len(questions)} 条")
    print(f"并发梯度: {concurrency_levels}")

    # 预热
    if args.warmup:
        print("\n>>> 预热请求...")
        warmup = call_chat_api(questions[0], "warmup")
        print(f"    预热完成: {warmup['time_ms']}ms")

    # 执行测试
    all_results = []
    for c in concurrency_levels:
        result = run_concurrency_test(c, questions)
        all_results.append(result)

    print_report(all_results)

    # 保存报告
    os.makedirs("reports", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)
    print(f"\n报告已保存: {args.output}")


if __name__ == "__main__":
    main()