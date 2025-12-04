from fastapi import FastAPI, UploadFile, File
from typing import Dict, Any, List
from io import BytesIO

from PIL import Image
import numpy as np
from paddleocr import PaddleOCR

app = FastAPI()

# 最简单、最稳的初始化：只指定语言，其他全部默认
ocr = PaddleOCR(
    lang="ch",      # 中文为主
    show_log=False  # 日志少一点，容器里更干净
)


@app.post("/ocr")
async def ocr_image(file: UploadFile = File(...)) -> Dict[str, Any]:
    """
    接受一张图片，返回统一格式的文字框列表：
    {
      "boxes": [
        { "x": ..., "y": ..., "width": ..., "height": ..., "text": "...", "confidence": 0.99 },
        ...
      ]
    }
    """
    content = await file.read()

    # 读成 RGB 图片
    image = Image.open(BytesIO(content)).convert("RGB")
    img_np = np.array(image)

    # 直接用默认参数调用
    result = ocr.ocr(img_np)

    boxes: List[Dict[str, Any]] = []

    # result 结构：list[ list[ [poly, (text, score)], ... ] ]
    for page in (result or []):          # 一般只有一页
        for line in page:
            poly = line[0]               # [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
            text, score = line[1]

            xs = [float(p[0]) for p in poly]
            ys = [float(p[1]) for p in poly]
            x_min, x_max = min(xs), max(xs)
            y_min, y_max = min(ys), max(ys)

            boxes.append({
                "x": x_min,
                "y": y_min,
                "width": x_max - x_min,
                "height": y_max - y_min,
                "text": text,
                "confidence": float(score),
            })

    return {"boxes": boxes}
