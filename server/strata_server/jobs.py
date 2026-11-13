from __future__ import annotations
import json
import threading
import time
from pathlib import Path
from .data import atomic_json, job_id

TERMINAL = {"complete", "failed", "cancelled"}


class FileJobs:
    def __init__(self, root: Path, recover=True):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        if recover:
            for record in root.glob("*/job.json"):
                data = json.loads(record.read_text())
                if data.get("phase") not in TERMINAL:
                    self.update(data["id"], phase="failed", message="Server restarted. Submit a new reconstruction; uploaded files are retained.")

    def directory(self, ident): return self.root / job_id(ident)

    def get(self, ident):
        path = self.directory(ident) / "job.json"
        if not path.exists(): raise KeyError(ident)
        return json.loads(path.read_text())

    def put(self, record): atomic_json(self.directory(record["id"]) / "job.json", record)

    def cancelled(self, ident): return (self.directory(ident) / ".cancelled").exists()

    def cancel(self, ident):
        self.directory(ident).joinpath(".cancelled").touch()
        return self.update(ident, phase="cancelled", message="Cancelled by client")

    def update(self, ident, **changes):
        with self.lock:
            record = self.get(ident)
            if self.cancelled(ident): changes.update(phase="cancelled", message="Cancelled by client")
            record.update(changes, updated=time.time())
            self.put(record)
            return record

    def records(self):
        return sorted((self.get(p.parent.name) for p in self.root.glob("*/job.json")), key=lambda d: d["created"], reverse=True)
