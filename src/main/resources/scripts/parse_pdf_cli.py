import sys
import json
import os
import requests
import io

# 强制 stdout 使用 UTF-8
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# API 地址（与启动服务时保持一致）
MINERU_API_URL = os.environ.get('MINERU_API_URL', 'http://127.0.0.1:8001/file_parse')

def parse_pdf_via_api(pdf_path):
    """
    通过 MinerU API 解析 PDF
    返回: {"success": bool, "content": str, "error": str}
    """
    try:
        if not os.path.exists(pdf_path):
            raise FileNotFoundError(f"PDF 文件不存在: {pdf_path}")

        # 以二进制上传文件
        with open(pdf_path, 'rb') as f:
            files = {'files': (os.path.basename(pdf_path), f)}
            response = requests.post(
                MINERU_API_URL,
                files=files,
                timeout=300  # 根据文档大小可调整
            )

        response.raise_for_status()
        result = response.json()

        # 根据 MinerU API 实际返回格式解析
        # 常见格式1: {"success": true, "content": "..."}
        # 常见格式2: 直接返回 Markdown 字符串
        if isinstance(result, dict):
            if 'content' in result:
                return {"success": True, "content": result['content']}
            elif 'data' in result and 'content' in result['data']:
                return {"success": True, "content": result['data']['content']}
            else:
                # 如果返回结构未知，将整个 JSON 转为字符串
                return {"success": True, "content": json.dumps(result, ensure_ascii=False)}
        elif isinstance(result, str):
            return {"success": True, "content": result}
        else:
            return {"success": True, "content": str(result)}

    except requests.exceptions.Timeout:
        return {"success": False, "error": "API 请求超时（超过300秒）"}
    except requests.exceptions.ConnectionError:
        return {"success": False, "error": f"无法连接到 API 服务，请检查服务是否启动（{MINERU_API_URL}）"}
    except requests.exceptions.RequestException as e:
        return {"success": False, "error": f"请求失败: {str(e)}"}
    except Exception as e:
        return {"success": False, "error": f"解析失败: {str(e)}"}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "缺少 PDF 路径参数"}))
        sys.exit(1)

    pdf_path = sys.argv[1]
    result = parse_pdf_via_api(pdf_path)
    print(json.dumps(result, ensure_ascii=False))