FROM python:3.13-slim-bookworm
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

COPY requirements.txt ./
RUN python -m pip install --no-cache-dir -r requirements.txt

COPY *.py ./model/

USER 10001:10001
EXPOSE 8000
CMD ["python", "-m", "model.service"]
