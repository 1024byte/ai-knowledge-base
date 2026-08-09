"""
RAG 系统自动化评估入口

一键执行完整评估流程，生成最终报告。

使用方式:
  python run_all.py                    # 完整评估（检索 + 生成 + 消融）
  python run_all.py --skip-gen         # 仅检索评估 + 消融（跳过耗时的生成评估）
  python run_all.py --skip-ablation    # 跳过消融实验
  python run_all.py --retrieval-only   # 仅检索评估
"""

import os
import sys
import argparse
import subprocess
import time
from datetime import datetime

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(EVAL_DIR)
sys.path.insert(0, EVAL_DIR)


def run_step(name: str, script: str, args: list = None) -> bool:
    """运行单个评估步骤，返回是否成功"""
    print(f"\n{'=' * 60}")
    print(f"  [{name}] 开始...")
    print(f"{'=' * 60}")
    start = time.time()

    cmd = [sys.executable, script] + (args or [])
    result = subprocess.run(cmd, cwd=EVAL_DIR)

    elapsed = time.time() - start
    if result.returncode == 0:
        print(f"  [{name}] 完成 (耗时 {elapsed:.1f}s)")
        return True
    else:
        print(f"  [{name}] 失败 (退出码 {result.returncode})")
        return False


def main():
    parser = argparse.ArgumentParser(description="RAG 系统自动化评估")
    parser.add_argument("--skip-gen", action="store_true", help="跳过生成评估（RAGAS，耗时较长）")
    parser.add_argument("--skip-ablation", action="store_true", help="跳过消融实验")
    parser.add_argument("--retrieval-only", action="store_true", help="仅执行检索评估")
    parser.add_argument("--file", default="data/test_queries.json", help="测试集路径")
    parser.add_argument("--output-dir", default="reports", help="报告输出目录")
    args = parser.parse_args()

    os.makedirs(os.path.join(EVAL_DIR, args.output_dir), exist_ok=True)

    total_start = time.time()
    print("╔══════════════════════════════════════════════════════╗")
    print("║        RAG 系统自动化评估                             ║")
    print(f"║        开始时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}                  ║")
    print(f"║        测试集: {args.file}                            ║")
    print("╚══════════════════════════════════════════════════════╝")

    results = {}

    # ===== 步骤 1: 数据集校验 =====
    run_step("数据集校验", "validate_dataset.py", ["--file", args.file])

    # ===== 步骤 2: 检索评估 =====
    retrieval_ok = run_step(
        "检索评估",
        "retrieval_eval.py",
        ["--file", args.file, "--output", f"{args.output_dir}/retrieval_report.json"]
    )
    results["retrieval"] = retrieval_ok

    if args.retrieval_only:
        print("\n>>> --retrieval-only 模式，跳过后续步骤")
        _finish(total_start, results)
        return

    # ===== 步骤 3: 生成评估（可选） =====
    if not args.skip_gen:
        gen_ok = run_step(
            "生成评估 (RAGAS)",
            "generation_eval.py",
            ["--file", args.file, "--output", f"{args.output_dir}/generation_report.json"]
        )
        results["generation"] = gen_ok
    else:
        print("\n>>> 跳过生成评估 (--skip-gen)")

    # ===== 步骤 4: 消融实验（可选） =====
    if not args.skip_ablation:
        ablation_ok = run_step(
            "消融实验",
            "ablation.py",
            ["--file", args.file, "--output", f"{args.output_dir}/ablation_report.json"]
        )
        results["ablation"] = ablation_ok
    else:
        print("\n>>> 跳过消融实验 (--skip-ablation)")

    # ===== 步骤 5: 汇总报告 =====
    run_step(
        "汇总报告",
        "report.py",
        ["--output", f"{args.output_dir}/final_report.md"]
    )

    _finish(total_start, results)


def _finish(total_start: float, results: dict):
    elapsed = time.time() - total_start
    print(f"\n{'=' * 60}")
    print(f"  评估完成! 总耗时 {elapsed:.1f}s ({elapsed/60:.1f}min)")
    print(f"  报告位置: reports/final_report.md")
    print(f"{'=' * 60}")

    print("\n  步骤执行结果:")
    for name, ok in results.items():
        status = "OK" if ok else "FAIL"
        print(f"    [{status}] {name}")

    if not all(results.values()):
        print("\n  WARNING: 部分步骤失败，报告可能不完整")


if __name__ == "__main__":
    main()