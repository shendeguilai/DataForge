FROM docker:27.5.1-cli
RUN apk add --no-cache python3
WORKDIR /runner
COPY service.py .
ENTRYPOINT ["python3", "-u", "service.py"]
