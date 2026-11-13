import argparse
from pathlib import Path
import uvicorn
from .api import create_app, load_key

parser = argparse.ArgumentParser(description="Run your own Strata GPU server")
parser.add_argument("--host", default="0.0.0.0")
parser.add_argument("--port", type=int, default=8787)
parser.add_argument("--data", type=Path, default=Path("strata-data"))
args = parser.parse_args()
root = args.data.resolve(); key = load_key(root)
print(f"Strata server port: {args.port}")
print("Enter this machine's LAN IP address in the phone, for example http://YOUR-LAN-IP:8787")
print(f"Server access key: {key}")
print("Keep this key private. HTTP is for a trusted LAN; use HTTPS when exposing a remote server.")
uvicorn.run(create_app(root, key), host=args.host, port=args.port, access_log=False)
