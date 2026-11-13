FROM ghcr.io/nerfstudio-project/nerfstudio:1.1.5
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN python -m pip install --no-cache-dir -r requirements.txt
COPY strata_server /app/strata_server
ENV PYTHONUNBUFFERED=1 QT_QPA_PLATFORM=offscreen
EXPOSE 8787
ENTRYPOINT ["python", "-m", "strata_server"]
CMD ["--data", "/data"]
