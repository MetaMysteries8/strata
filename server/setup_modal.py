"""Run explicitly on your computer to authenticate and deploy your own endpoint."""
import argparse
import secrets
import subprocess

parser = argparse.ArgumentParser(description="Set up your own optional Strata Modal endpoint")
parser.add_argument("--deploy", action="store_true", help="Deploy after the real Modal browser login and secret setup")
args = parser.parse_args()
subprocess.run(["modal", "token", "new"], check=True)
key = secrets.token_urlsafe(32)
import modal
# The SDK sends the secret directly; it never appears in subprocess arguments.
# Refuse to silently replace an existing deployment's key.
modal.Secret.objects.create("strata-server-auth", {"STRATA_API_KEY": key})
print("Phone server access key:", key)
print("Create a workspace Proxy Token in Modal Settings. In the phone enter token-id.token-secret in the Modal proxy token field.")
print("The deployment command prints the API URL. GPU jobs and storage may incur charges on your Modal account.")
if args.deploy: subprocess.run(["modal", "deploy", "modal_app.py"], check=True)
else: print("Deploy when ready: modal deploy modal_app.py")
