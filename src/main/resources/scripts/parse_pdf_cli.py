import sys
import json
import os
import requests
import io

# 强制 stdout 使用 UTF-8
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# API 地址
MINERU_API_URL = os.environ.get('MINERU_API_URL', 'http://127.0.0.1:8001/file_parse')

def parse_pdf_via_api(pdf_path, params=None):
    """
    通过 MinerU API 解析 PDF
    params: 解析参数字典，用于控制解析行为
    """
    try:
        if not os.path.exists(pdf_path):
            raise FileNotFoundError(f"PDF 文件不存在: {pdf_path}")

        # 默认参数（可根据需要调整）
        default_params = {
            "backend": "hybrid-engine",        # 解析引擎
            "parse_method": "auto",            # auto / text / ocr
            "lang_list": ["ch", "en"],         # 语言列表
            "enable_table": True,              # 启用表格识别
            "enable_formula": True,            # 启用公式识别
            "enable_figure": False,            # 🔑 关闭图片提取，减少噪音
            "enable_ocr": False,               # 🔑 文字型PDF可关闭OCR
            "filter_header_footer": True,      # 🔑 过滤页眉页脚
            "filter_page_number": True,        # 🔑 过滤页码
            "batch_size": 1,                   # 6G显存建议设为1
            "return_md": True,                 # 返回Markdown
            "return_middle_json": False,       # 不返回中间JSON
            "return_images": False,            # 不返回图片
        }

        # 合并自定义参数
        if params:
            default_params.update(params)

        # 构建请求（stream=True 启用流式传输，避免全量加载到内存）
        with open(pdf_path, 'rb') as f:
            files = {'files': (os.path.basename(pdf_path), f)}

            # 🔑 关键：通过 data 传递参数（JSON 字符串）
            # 注意：不同版本的 MinerU 可能对 data 格式要求不同
            # 方式一：直接传 JSON 字符串（推荐）
            data = {'params': json.dumps(default_params)}

            # 方式二：如果上面不行，尝试展平传参（部分版本支持）
            # data = {k: json.dumps(v) if isinstance(v, list) else str(v)
            #         for k, v in default_params.items()}

            response = requests.post(
                MINERU_API_URL,
                files=files,
                data=data,
                timeout=600,  # 大文档延长超时
                stream=True   # 🔑 流式接收响应，避免 response.content 全量加载
            )

        response.raise_for_status()
        # 使用 json.load() 从原始流中增量解析，避免 response.content 中间副本
        # json.load() 逐 chunk 读取并构建 dict，不会在内存中保留完整 bytes 副本
        #
        # 注意：requests 在 stream=True 时会将 response.raw.decode_content 设为 False，
        # 导致 read() 返回未解压的 gzip 数据（0x1f 0x8b 开头）。
        # 必须强制启用解压，否则 json.load() 会因二进制数据而报 UTF-8 解码错误
        response.raw.decode_content = True
        result = json.load(response.raw)

        # 解析返回结果（根据实际格式调整）
        if isinstance(result, dict):
            # 尝试多种可能的返回格式
            if 'content' in result:
                content = result['content']
            elif 'data' in result and 'content' in result['data']:
                content = result['data']['content']
            elif 'markdown' in result:
                content = result['markdown']
            elif 'md_content' in result:
                content = result['md_content']
            else:
                # 如果找不到，返回完整JSON
                return {"success": True, "content": json.dumps(result, ensure_ascii=False, indent=2)}

            # 可选：后处理清洗（作为兜底）
            # content = clean_markdown(content)

            return {"success": True, "content": content}
        elif isinstance(result, str):
            return {"success": True, "content": result}
        else:
            return {"success": True, "content": str(result)}

    except requests.exceptions.Timeout:
        return {"success": False, "error": "API 请求超时（超过600秒）"}
    except requests.exceptions.ConnectionError:
        return {"success": False, "error": f"无法连接到 API 服务（{MINERU_API_URL}）"}
    except requests.exceptions.RequestException as e:
        return {"success": False, "error": f"请求失败: {str(e)}"}
    except Exception as e:
        return {"success": False, "error": f"解析失败: {str(e)}"}


def clean_markdown(md_text):
    """后处理清洗：移除页眉页脚、图片占位符等噪音"""
    import re
    # 移除图片占位符
    md_text = re.sub(r'!\[\]\(images/.*?\)', '', md_text)
    # 移除版权声明等
    md_text = re.sub(r'版权所有.*?保留一切权利。', '', md_text, flags=re.DOTALL)
    # 移除页码
    md_text = re.sub(r'第\s*\d+\s*页', '', md_text)
    md_text = re.sub(r'Page\s*\d+', '', md_text, flags=re.IGNORECASE)
    # 清理多余空行
    md_text = re.sub(r'\n\s*\n+', '\n\n', md_text)
    return md_text.strip()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        json.dump({"success": False, "error": "缺少 PDF 路径参数"}, sys.stdout, ensure_ascii=False)
        sys.exit(1)

    pdf_path = sys.argv[1]

    # 可以在这里传入自定义参数（覆盖默认值）
    custom_params = {
        "filter_header_footer": True,
        "filter_page_number": True,
        "enable_figure": False,
        "enable_ocr": False,
        "batch_size": 1,
    }

    result = parse_pdf_via_api(pdf_path, params=custom_params)
    # 使用 json.dump() 直接写入 stdout，避免 json.dumps() 创建完整字符串副本
    json.dump(result, sys.stdout, ensure_ascii=False)
    sys.stdout.write('\n')