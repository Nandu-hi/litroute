# LitRoute with the JavaFX GUI, viewable in a browser (noVNC).
#   docker build -t litroute-base .
#   docker run --rm -p 6080:6080 litroute-base   ->  http://localhost:6080/vnc.html
FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        maven python3 python3-venv python3-pip \
        xvfb x11vnc novnc websockify fluxbox xterm \
        libgtk-3-0 libgl1 libglx-mesa0 libgl1-mesa-dri libxtst6 libxrender1 libxi6 libasound2 \
        fonts-dejavu-core ca-certificates curl zstd \
    && rm -rf /var/lib/apt/lists/*

# Ollama inside the container, so LitRoute's local-LLM mode (loopback only) works.
# The llama3.2:3b weights (~2 GB) are baked in; nothing is downloaded at run time.
# Both downloads retry: a dropped connection or a pull stalled near 100% would otherwise fail the build
# (or, for the pull, silently leave the image without the model). A retried pull resumes its partial file.
RUN for i in 1 2 3; do curl -fsSL https://ollama.com/install.sh | sh && break; sleep 5; done \
    && command -v ollama \
    && (ollama serve >/dev/null 2>&1 &) \
    && for i in $(seq 1 30); do curl -s localhost:11434 >/dev/null && break; sleep 1; done \
    && for i in 1 2 3 4 5; do timeout 600 ollama pull llama3.2:3b && break; echo "pull attempt $i failed, retrying"; sleep 5; done \
    && ollama show llama3.2:3b >/dev/null \
    && (pkill ollama || true)

WORKDIR /app

# SPECTER2 -> ONNX (needs network at build time only; the venv is dropped afterwards).
# The model lives in /app/models, outside the data volume, so a new image brings its own model.
COPY scripts/model-export scripts/model-export
RUN SPECTER2_OUT_DIR=/app/models scripts/model-export/setup_model.sh \
    && rm -rf scripts/model-export/.venv /root/.cache

# Dependencies first (cached layer), then sources
COPY pom.xml .
RUN mvn -q -B dependency:copy-dependencies -DoutputDirectory=target/lib
COPY src/main src/main
RUN mvn -q -B -DskipTests compile

COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /root/.litroute/profiles /root/.litroute/data

EXPOSE 6080
VOLUME ["/root/.litroute"]
ENTRYPOINT ["/entrypoint.sh"]
