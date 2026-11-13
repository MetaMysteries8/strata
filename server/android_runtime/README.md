# Modal from Android: phone setup (0.2.6)

This companion runs the official Modal CLI inside a Termux Ubuntu userland. No PC, root access, Android VM, or phone CUDA support is required. Ubuntu and Python packages download during first setup. Termux is a separate app; this is not a bundled Android Linux VM.

1. Install Strata Preview and open Settings → Modal account → One-time phone setup.
2. Get Termux from the provided F-Droid link. Save **Strata-Modal-Android.zip** into Downloads with that exact filename. The same runtime ZIP is bundled in the APK and available through Save runtime ZIP.
3. Tap Copy command, open Termux, and paste it. Allow Termux's storage prompt. It installs proot-distro, a separate Ubuntu userland named strata-modal, Python, and Modal 1.6.4. The installation does not enable Termux external-app command permissions.
4. When the runtime prints its pairing key, copy it into Strata's Phone runtime pairing key field and connect. The key controls this phone-local companion; it is not your Modal account API token.
5. Tap **Sign in with Modal**. Modal's real website opens in your normal browser. Sign in, compare/enter the CLI code if requested, and approve. Return to Strata. The app displays Signed in only after the official CLI completes and verifies the credentials with `modal token info`.
6. Tap **Deploy and connect** and review the native deployment confirmation. This creates a private per-installation endpoint, Modal secret, Volume, Dict, and workspace proxy token in your account. Image builds and storage can incur costs. Reconstruction requests can incur GPU charges. Login alone performs no deployment or scan upload.
7. After deployment, the companion checks authenticated endpoint health and hands its endpoint credentials to Android's encrypted server settings. If the app restarted during deployment, tap **Use endpoint**. Open a scan, choose My GPU server, and explicitly upload it for reconstruction.

Keep Termux running and allow it to stay active during login/deployment. Android may stop background processes. Restart the runtime by opening Termux and running:

```sh
bash "$HOME/strata-phone/strata-modal/android_runtime/setup.sh"
```

The saved pairing key remains the same. First setup uses Ubuntu 24.04 on current proot-distro; older proot-distro falls back to its Ubuntu distribution alias. Linux/Python compatibility and Android process behavior still need testing on an actual phone. This environment has not completed a real Modal account login or deployment.

The companion listens only on 127.0.0.1:8788. Every request requires the runtime key. Browser Origin requests are rejected, no CORS is enabled, and it does not accept arbitrary commands or user-supplied deployment code. The APK opens login links only on HTTPS modal.com / www.modal.com. Main Modal API credentials stay in the companion's isolated `modal.toml` under private Termux/Ubuntu storage. The Android app receives endpoint keys, not the account token. The runtime pairing key is saved using Android Keystore encryption.

The CLI token requests a 7-day lifetime subject to workspace policy. Sign in again when it expires. Cancel setup task stops the local CLI process group. A cancelled deployment can leave already-created cloud resources; inspect your Modal dashboard before retrying. Sign out of CLI locally forgets this companion's account profile. It does not revoke account tokens, remove endpoint keys, stop your deployed app, or delete stored scans. Manage those resources explicitly in your Modal dashboard.

Companion state lives under ~/.local/share/strata-modal inside the separate Ubuntu userland. Deployment logs are private files there. Never post its pairing key, deployment.json, connection.json, or modal.toml publicly. No real account keys or user data are included in the distributed runtime ZIP.

Primary references: https://github.com/termux/proot-distro , https://f-droid.org/packages/com.termux/ , https://modal.com/docs/cli/latest/token , https://modal.com/docs/sdk/py/latest/Workspace , https://modal.com/docs/guide/webhook-proxy-auth .

## Updating an existing phone runtime

Stop the existing runtime with Ctrl+C in its Termux session. Save the new Strata-Modal-Android.zip in Downloads using that exact name, replacing the older ZIP. Run the setup command from the app again. It updates code/packages while reusing the separate Ubuntu environment and private credentials/data. Launching a second runtime while the first still owns port 8788 does not update the running process.

The app warns if the connected runtime is older than 0.3.0. Copy setup diagnostics shares only version/platform/phase information; it excludes credentials and raw provider logs. Full errors stay in private deploy.log/deploy-error.log files. A cancelled or failed deployment can leave cloud resources already created; inspect Modal before retrying.

Account checks are cancellable and refreshed after five minutes. Tap **Recheck account** to retry immediately after cancellation or after fixing a network/account problem; this verifies the saved profile without starting another browser login. The setup script releases its Termux wake lock when the runtime exits. Deployment configurations are stored separately under workspaces/<hashed-provider-workspace-id>/deployment.json. A legacy 0.2.1 deployment is migrated only if Modal confirms its proxy token is owned by the current workspace. Otherwise a new workspace-specific endpoint is created after your deployment confirmation. Existing cloud assets are not deleted. The separate server settings may still point to an earlier endpoint until you choose Deploy and connect / Use endpoint.

## Restart service after one-time setup

Use Settings → Modal account → Restart runtime service. Copy restart command, open Termux, paste it, keep the session open, then Check connection in Strata. The new launcher is:

```sh
bash "$HOME/strata-phone/strata-modal/android_runtime/start.sh"
```

It reuses Ubuntu/Python, private account data, and the pairing key. It checks that installation exists and avoids package installation in the Termux restart path. If Python dependencies need updating, the Linux launcher repairs them. The app's copied command falls back to setup.sh on older installs that lack start.sh. A full device reboot or closing Termux requires restarting the local companion for login/deployment controls; an already-running cloud scan continues independently.

## Renew a Modal account token

Settings → Modal token and account access → Renew Modal token invokes the same official CLI browser flow as sign-in. Approve on Modal's site and return to Strata; it verifies the saved profile before showing Signed in. Where the CLI reports it, the expiry date is shown. Recheck account tests the existing token without creating another token. Renew does not deploy or upload data and does not automatically revoke older account tokens. The account token is distinct from the printed phone pairing key and protected scan-endpoint keys.

## Update without interrupting a scan

Wait for a current scan to finish before app installation or server redeployment. Then stop the companion with Ctrl+C, replace the Downloads runtime ZIP using its exact name, rerun setup to update code/packages, and deploy the new server only when ready. Account credentials and stored scans remain in the existing private environment. No update can guarantee GPU scheduling availability.

## NVIDIA banner with no reconstruction messages

The NVIDIA container license banner indicates startup and does not establish that a scan is running or hung. Before 0.2.4, engine output was only written to the private engine.log, so Modal’s console could look silent during training. Updated workers publish entry, volume reload, named stage, engine output, and process-alive heartbeat messages. These messages require the updated server to be deployed; they cannot appear retroactively in an existing old worker. Check Strata Processing and provider GPU/CPU metrics for a current scan. Avoid redeploying over active work until deciding whether to keep or cancel that work.

## Worker heartbeat versus a successful status request (0.2.5)

Last server contact means the CPU API answered; it does not prove the GPU worker is making progress. Worker last reported is published by worker execution at startup, stage transitions, and roughly every 30 seconds during an engine subprocess. Process runtime is the reported elapsed time, not an estimate of remaining work. A 90-second-old heartbeat prompts checking provider logs/metrics, without automatically failing or canceling the scan. Older servers cannot report this field. Updated workers mark loading/storage failures in the job record; these fixes require deploying the new server source and cannot retrofit an old running worker.

## Deployment-name correction (0.2.6)

This update preserves the generated resource names inside both deployed images. Prior versions could fall back to the generic job table during remote imports. Existing records may therefore be in the old default table; the update does not automatically migrate active jobs or delete any cloud storage. Let an old call finish or cancel that specific call before switching, keep the original capture, then submit a fresh reconstruction after updating.

Stop the local runtime with Ctrl+C, replace Strata-Modal-Android.zip in Downloads, and rerun the app's setup command to unpack the new code. It reuses installed Ubuntu and credentials. Then Deploy and connect to update the same app. An existing 0.2.5 APK can use this external ZIP; the optional 0.2.6 APK bundles it as well. Do not remove the existing deployment or its volumes. GPU initialization has a separate five-minute timeout; GPU allocation wait is outside that limit. See DEPLOYMENT-AUDIT.md in the source for evidence and limitations.

## GitHub server updates (0.3.0)

Refresh the phone runtime once using the latest release ZIP and the app's setup command; installed Ubuntu and private state are reused. Settings → Modal account → Update server from GitHub then checks the current deployment's job table, downloads the latest stable release manifest/runtime, verifies SHA-256 and safe ZIP paths, and deploys the same saved app/resource names. It waits for authenticated health with the expected version before switching the saved source and connection. Routine deployment reuses that saved source. A health failure keeps the old local pointers but cannot undo cloud-side changes already applied. Legacy jobs in a different Dict are not detected: review those before updating.

The stable companion continues running from the installed setup source; server source updates live privately under its state directory. If a later release requires a newer companion, rerun setup with that release's ZIP. GitHub requests contain no Modal account credentials. Android downloads/installations and cloud deployment are separate explicit actions.
