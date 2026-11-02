FROM python:3.11-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-eng fonts-dejavu-core fonts-comic-neue wamerican libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1000 --create-home translator \
    && mkdir /models && chown translator:translator /models
WORKDIR /app
COPY requirements.txt .
# torchvision must come from the CPU index too, or pip replaces torch with a multi-GB CUDA build.
RUN pip install --no-cache-dir torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
ENV XDG_DATA_HOME=/models XDG_CACHE_HOME=/models/cache \
    ARGOS_DEVICE_TYPE=cpu OMP_NUM_THREADS=2 PYTHONUNBUFFERED=1
USER translator
EXPOSE 8091
CMD ["python", "app.py"]
