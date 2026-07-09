import sys
import json
import subprocess
import os
import shutil
import tempfile

# 强制 stdout 使用 UTF-8
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# 指定 mineru.exe 绝对路径
MINERU_EXE = os.environ.get('MINERU_HOME', r"E:\Anaconda_envs\envs\pytorch_gpu\Scripts\mineru.exe")
if not os.path.exists(MINERU_EXE):
    import shutil
    alt = shutil.which('mineru')
    if alt:
        MINERU_EXE = alt
    else:
        # 如果都找不到，抛出明确错误
        print(json.dumps({"success": False, "error": "找不到 mineru.exe，请设置 MINERU_HOME 环境变量"}))
        sys.exit(1)

def read_md_file_with_auto_encoding(file_path):
    """尝试多种编码读取 Markdown 文件"""
    encodings = ['utf-8', 'gbk', 'gb2312', 'gb18030', 'latin-1']
    for enc in encodings:
        try:
            with open(file_path, "r", encoding=enc) as f:
                return f.read()
        except UnicodeDecodeError:
            continue
    with open(file_path, "r", encoding='utf-8', errors='ignore') as f:
        return f.read()

def parse_pdf_with_cli(pdf_path):
    try:
        pdf_abs = os.path.abspath(pdf_path)
        if not os.path.exists(pdf_abs):
            raise Exception(f"PDF 文件不存在: {pdf_abs}")

        output_dir = tempfile.mkdtemp(prefix="mineru_output_")

        cmd = [
            MINERU_EXE,
            "-p", pdf_abs,
            "-o", output_dir,
            "--method", "auto",
            "--lang", "ch",
            "-f", "true",
            "-t", "true"
        ]

        # 使用二进制模式运行，不依赖 text=True，自己控制编码
        result = subprocess.run(cmd, capture_output=True, timeout=300)

        # 检查是否执行成功（只看 returncode）
        if result.returncode != 0:
            # stderr 可能是 GBK 编码，尝试解码
            try:
                err_msg = result.stderr.decode('gbk')
            except UnicodeDecodeError:
                err_msg = result.stderr.decode('utf-8', errors='ignore')
            raise Exception(f"mineru 执行失败: {err_msg}")

        # 查找生成的 Markdown 文件
        md_file = None
        for root, dirs, files in os.walk(output_dir):
            for file in files:
                if file.endswith(".md"):
                    md_file = os.path.join(root, file)
                    break
            if md_file:
                break

        if not md_file or not os.path.exists(md_file):
            raise Exception("未找到生成的 Markdown 文件")

        # 使用自动编码读取
        content = read_md_file_with_auto_encoding(md_file)

        shutil.rmtree(output_dir, ignore_errors=True)

        #只输出 JSON 到 stdout
        print(json.dumps({"success": True, "content": content}, ensure_ascii=False))

    except subprocess.TimeoutExpired:
        shutil.rmtree(output_dir, ignore_errors=True)
        print(json.dumps({"success": False, "error": "解析超时（超过300秒）"}))
    except Exception as e:
        print(json.dumps({"success": False, "error": str(e)}))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "缺少 PDF 路径参数"}))
        sys.exit(1)
    pdf_path = sys.argv[1]
    parse_pdf_with_cli(pdf_path)