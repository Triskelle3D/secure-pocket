# Multi-architecture index digest. Refresh deliberately and review with a
# vulnerability scan instead of silently following a mutable tag.
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV LINK_DEFAULT_LIBRARY_ROOT=/data \
    LINK_DEFAULT_DOCLING_URL=https://docling:5001 \
    LINK_DEFAULT_DOCLING_ENABLED=true
ENV PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

RUN addgroup --system pocket && adduser --system --ingroup pocket pocket \
    && mkdir -p /data /config /keys /docling-ca /docling-server /docling-auth /tls-state \
    && chown -R pocket:pocket /data /config /keys \
    && chown -R 1001:0 /docling-ca /docling-server /docling-auth /tls-state \
    && apt-get update \
    && apt-get install --no-install-recommends --only-upgrade -y zlib1g \
    && rm -rf /var/lib/apt/lists/*
COPY requirements-runtime.txt ./
RUN python -m pip install --no-cache-dir "pip==26.2" \
    && python -m pip install --no-cache-dir --requirement requirements-runtime.txt \
    && python -m pip check \
    && python -m pip uninstall -y pip
COPY --chown=pocket:pocket secure_pocket_link.py docling_client.py link_crypto.py settings.html ./
COPY --chown=1001:0 generate_docling_tls.py start_docling_secure.py ./
USER pocket

EXPOSE 8732 8733
CMD ["python", "secure_pocket_link.py", "--port", "8732", "--admin-host", "0.0.0.0", "--admin-port", "8733", "--config", "/config/config.json"]
