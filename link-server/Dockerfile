FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV LINK_DEFAULT_LIBRARY_ROOT=/data \
    LINK_DEFAULT_DOCLING_URL=http://docling:5001 \
    LINK_DEFAULT_DOCLING_ENABLED=true
WORKDIR /app

RUN addgroup --system pocket && adduser --system --ingroup pocket pocket \
    && mkdir -p /data /config \
    && chown -R pocket:pocket /data /config
COPY --chown=pocket:pocket secure_pocket_link.py docling_client.py settings.html ./
USER pocket

EXPOSE 8732 8733
CMD ["python", "secure_pocket_link.py", "--port", "8732", "--admin-host", "0.0.0.0", "--admin-port", "8733", "--config", "/config/config.json"]
