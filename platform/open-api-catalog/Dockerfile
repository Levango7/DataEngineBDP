FROM python:3.11-slim

WORKDIR /app

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 拷贝源码
COPY . .

# 安装包
RUN pip install --no-cache-dir -e .

EXPOSE 8090

CMD ["python", "main.py"]