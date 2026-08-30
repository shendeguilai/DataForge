FROM python:3.13-slim-bookworm

ARG PANDOC_VERSION=3.8.2.1
ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && case "${TARGETARCH:-amd64}" in \
         amd64) deb_arch=amd64 ;; \
         arm64) deb_arch=arm64 ;; \
         *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
       esac \
    && curl --fail --location --silent --show-error \
         "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-1-${deb_arch}.deb" \
         --output /tmp/pandoc.deb \
    && apt-get install -y --no-install-recommends /tmp/pandoc.deb \
    && pandoc --version | head -n 1 | grep -Fqx "pandoc ${PANDOC_VERSION}" \
    && rm -f /tmp/pandoc.deb \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --user-group --create-home --home-dir /home/csp-studio csp-studio \
    && mkdir -p /app \
    && chown -R csp-studio:csp-studio /app /home/csp-studio

WORKDIR /app
COPY requirements.lock.txt ./
RUN python -m pip install --no-cache-dir --requirement requirements.lock.txt

COPY --chown=csp-studio:csp-studio converter.py studio_core.py service_app.py CSP_初赛模板.docx ./
COPY --chown=csp-studio:csp-studio samples ./samples

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

USER 10002:10002
EXPOSE 8765
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=10s \
  CMD ["python", "-c", "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=3))['status']=='UP'"]

ENTRYPOINT ["python", "-m", "uvicorn", "service_app:app", "--host", "0.0.0.0", "--port", "8765", "--workers", "1", "--no-server-header", "--no-access-log"]
