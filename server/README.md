# Strata GPU server 0.2.6

Run reconstruction on your own computer or optional Modal account. The phone always captures locally; choosing a server reconstruction opens an explicit upload confirmation. The service produces real Gaussian PLY files or triangle meshes, not synthetic demo geometry.

## LAN server using Docker

Use Linux, or Windows with WSL2, an NVIDIA driver, Docker Compose supporting `gpus: all`, and NVIDIA Container Toolkit. Docker must be able to access the GPU. The image includes Nerfstudio 1.1.5; building it and installing its dependencies requires Internet access. After setup, reconstruction uses local engines and data. No vocabulary-tree/model download is requested by the COLMAP path.

From this directory:

```sh
docker compose up --build
```

The startup log prints a random **server access key**. Pair it on the phone in Settings with `http://YOUR-NUMERIC-LAN-IP:8787`, for example `http://192.168.1.20:8787`. Phone and server must reach each other on Wi-Fi; allow the private-network firewall rule for port 8787. Save and test, then open a scan and select **My GPU server** and Splat, Mesh, or Both.

Data and the key survive restarts in the `strata-data` Docker volume. One worker serializes GPU jobs; up to 16 nonterminal jobs may be queued. The server process marks interrupted jobs failed on restart rather than silently rerunning expensive work. A reconnect on the phone resumes polling/downloading; it does not resume a lost training process or repair a failed job. A new reconstruction creates a new job.

`docker compose down` stops it and preserves the volume. Do not use `down -v` unless you intend to permanently remove server data. Datasets/checkpoints remain on the server; deleting a phone scan does not remove remote copies. Inspect/manage that volume from the host. There is no automatic expiry or remote delete endpoint in this release.

### Native Python alternative

Use Python 3.10+, a working CUDA/PyTorch/gsplat Nerfstudio 1.1.5 installation, FFmpeg, and a CUDA-enabled COLMAP build on PATH. Installing only this service's requirements does not install reconstruction engines. Verify `ns-train splatfacto --help`, `ns-export gaussian-splat --help`, `ns-process-data images --help`, and `colmap patch_match_stereo --help` on your GPU host.

```sh
python -m pip install -r requirements.txt
python -m strata_server --host 0.0.0.0 --port 8787 --data ./strata-data
```

A random key is created as `strata-data/server.key` with restricted permissions. To use an existing private key, set `STRATA_API_KEY` in the host environment before startup. Never embed it in a public URL or commit it to source control. Health reports installed commands; it cannot prove that CUDA kernels or a particular GPU work.

### GPU budget and results

Quick uses Splatfacto for 7,000 iterations; Detail uses 30,000; Maximum uses Splatfacto-big for 50,000. Upstream documents roughly 6 GB for Splatfacto and 12 GB for its larger variant; real use varies. Try Quick/Detail first on a 12 GB RTX 4070. Maximum can run out of VRAM. Mesh work also needs CPU RAM/disk space and may take much longer than a phone preview.

Splat training uses provided poses and seed geometry with camera optimization. Both/Mesh re-estimate cameras with COLMAP, compute dense depth, fuse it, and reconstruct a Poisson surface. Both subsequently trains on that aligned dataset. Mesh scale/orientation are not guaranteed to match the phone's metric world. GLB and OBJ have vertex colors; STL stores geometry only. No texture atlas, watertightness, or printing readiness is guaranteed.

Recorded raw depth remains available as source data and seeded points; the RGB splat trainer does not consume it as calibrated depth supervision. The service makes a separate training manifest to avoid interpreting partial, differently calibrated depth as RGB-aligned depth. Original files are preserved.

## Phone login and deployment

Use the included [Android runtime](android_runtime/README.md) for phone-only login and deployment from the Strata Settings screen. It uses Termux/Ubuntu and the official Modal CLI.

## Optional computer deployment

This code was SDK-import checked, not deployed or tested against a Modal account/GPU. It can incur GPU, CPU, and storage charges when you deploy/use it. Authentication/deployment happen on your own computer; the phone pairs to the resulting endpoint.

1. Create/use your own Modal account and install `python -m pip install -r modal-requirements.txt`.
2. In this directory run `python setup_modal.py`. It invokes Modal's real browser login (`modal token new`), creates a named `strata-server-auth` secret through the SDK, and prints the phone access key. It refuses to overwrite an existing named secret; use its existing key or explicitly manage that secret in your own Modal dashboard. The setup script does not deploy by default.
3. Create a **workspace Proxy Token** in Modal Settings. Combine its token ID and secret with a period: `token-id.token-secret`.
4. Deploy with `modal deploy modal_app.py`. Alternatively the first-time setup script accepts `--deploy`. Copy the actual API URL Modal prints.
5. Pair the phone with that HTTPS API URL, the generated server access key, and the proxy token. Save and test, then explicitly request a remote reconstruction.

The web app uses `requires_proxy_auth=True` plus Strata's key. The CPU API spawns a background **L4** function and returns a job ID, so training does not occupy one long HTTP request. Job metadata/cancellation are in Modal Dict; datasets/checkpoints/artifacts are on a Modal Volume. The worker commits files before publishing completion. Reconnect retrieves status and completed artifacts. Cancel requests cancel the worker call. A failed worker is reported as failed, not as successful empty geometry.

Maximum is limited to one GPU worker container, not an account spending cap. CPU API concurrency is 16; uploads have size/queue limits. Set account budgets in your own Modal account if desired. To stop deployment use the Modal dashboard/CLI; volumes and dictionaries remain until you explicitly remove them. Do not paste your main Modal CLI credential into the phone: use a workspace proxy token and the separate server key.

## API protocol 1

Every request needs `X-Strata-Key`. Modal additionally accepts the workspace proxy credential as `Authorization: Bearer token-id.token-secret`. Redirects are not followed by the phone, so pair the final HTTPS URL.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/v1/health` | Provider, protocol, installed engines, upload limit |
| POST | `/v1/jobs?mode=splat&preset=detail` | Raw ZIP request body; optional UUID `X-Request-ID` for idempotency |
| GET | `/v1/jobs/{uuid}` | Actual phase, stage, message, artifact names |
| POST | `/v1/jobs/{uuid}/cancel` | Durable cancellation; no body |
| GET | `/v1/jobs/{uuid}/artifacts/{kind}` | Completed advertised artifact only |

Modes: `splat`, `mesh`, `both`. Presets: `quick`, `detail`, `max`. Artifact kinds: `splat`, `glb`, `obj`, `stl`, `points`. Reusing a request ID with the same settings returns its original job; different settings get 409. An interrupted upload gets a failed record rather than another GPU dispatch. Authentication runs before reading the body. Jobs use UUID directories and fixed artifact paths.

Uploads: 600 MiB compressed, 2 GiB expanded, 2,500 entries; root transforms.json with 12–600 posed calibrated frames. Unsafe/duplicate paths, symlinks, invalid calibration, missing images, non-finite matrices, and mismatched image dimensions are rejected. Server errors do not delete the phone's original dataset. Phone downloads are bounded to 1 GiB per artifact and validated before publishing new splat/GLB files.

## Verification and references

```sh
python -m pip install -r requirements.txt pytest httpx
PYTHONPATH=. python -m pytest -q tests
```

The tests exercise API auth, actual ZIP extraction, idempotency, cancellation of a real child process, malformed PLY validation, raw-depth manifest handling, and real trimesh GLB/STL conversion. They do not run COLMAP or Splatfacto on a GPU.

Primary references: [Nerfstudio Splatfacto](https://docs.nerf.studio/nerfology/methods/splat.html), [pinned model configuration](https://github.com/nerfstudio-project/nerfstudio/blob/v1.1.5/nerfstudio/models/splatfacto.py), [COLMAP CLI](https://colmap.github.io/cli.html), [Modal proxy auth](https://modal.com/docs/guide/webhook-proxy-auth), [background calls](https://modal.com/docs/guide/function-invocation-methods), [volumes](https://modal.com/docs/guide/volumes), [secret API](https://modal.com/docs/sdk/py/latest/Secret).
