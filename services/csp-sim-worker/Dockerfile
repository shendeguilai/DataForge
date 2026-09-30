FROM docker:27.5.1-cli
RUN apk add --no-cache python3
WORKDIR /worker
COPY worker.py .
ENTRYPOINT ["python3", "-u", "worker.py"]
