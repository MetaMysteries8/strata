"""Public status data only. Never copy raw CLI logs or account tokens into API responses."""
import re

ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")

def workspace_identity(output):
    match = re.search(r"Workspace:\s*([^\n]+?)\s*\((ac-[A-Za-z0-9_-]+)\)", ANSI.sub('', output))
    if not match:
        raise ValueError('Modal did not return a workspace identity. Reinstall the pinned runtime CLI.')
    return {'workspace': match.group(1).strip(), 'workspace_id': match.group(2)}

def token_expiry(output):
    # Read only the CLI's formatted date, never a token or arbitrary provider text.
    match = re.search(r'^Expires at:\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [A-Za-z0-9_+:/-]{1,32})\s*$', ANSI.sub('', output), re.M)
    return match.group(1) if match else ''

def failure_hint(error):
    # Classification avoids exposing a provider error that may contain credentials or login URLs.
    text = str(error).lower()
    if 'active strata jobs' in text:return 'Server update paused: finish or cancel active Strata jobs, then retry.'
    if 'release' in text or 'checksum' in text:return 'Release update could not be verified. Check the GitHub release and runtime version, then retry.'
    if 'workspace identity' in text:
        return 'The Modal CLI returned an unsupported account response. Reinstall the pinned phone runtime.'
    if any(word in text for word in ('unauthorized', 'authentication', 'token missing', 'expired', 'invalid token')):
        return 'Modal could not verify your credentials. Sign in again.'
    if any(word in text for word in ('billing', 'payment', 'credits', 'quota')):
        return 'Modal reported a billing or quota issue. Check your Modal account before retrying.'
    if any(word in text for word in ('permission', 'forbidden', 'not authorized')):
        return 'Your Modal account lacks access for this step. Check the selected workspace and permissions.'
    if any(word in text for word in ('connection', 'timeout', 'timed out', 'ssl', 'tls', 'dns', 'network')):
        return 'Could not reach Modal. Check your connection, then retry.'
    if any(word in text for word in ('modulenotfound', 'importerror', 'no module named')):
        return 'The phone runtime is missing a dependency. Restart its setup script to repair it.'
    return 'Modal setup failed. Check the private runtime log; its raw contents are not shared with the app.'
