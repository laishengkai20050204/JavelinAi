FROM paddlepaddle/paddle:3.0.0

# 1. 先强制把 numpy 打到 1.x（比如 1.24.4 或 1.26.4）
RUN pip install --no-cache-dir --force-reinstall "numpy==1.26.4" && \
    pip install --no-cache-dir \
        "paddleocr==2.7.0.3" \
        "opencv-python-headless<4.10" \
        fastapi \
        "uvicorn[standard]" \
        pillow \
        python-multipart

WORKDIR /app
COPY paddle_ocr_server.py /app/paddle_ocr_server.py

EXPOSE 8081
CMD ["uvicorn", "paddle_ocr_server:app", "--host", "0.0.0.0", "--port", "8081"]
