"""Cancel superseded release builds without holding a workflow-wide concurrency lock."""

import argparse
import json
import os
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ACTIVE_STATUSES = ("queued", "in_progress", "waiting", "pending", "requested")


class GitHubAPI:
    def __init__(self):
        self.base_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
        self.repository = os.environ["GITHUB_REPOSITORY"]
        self.token = os.environ["GH_TOKEN"]

    def request(self, path, method="GET"):
        request = Request(
            f"{self.base_url}/repos/{self.repository}/{path}",
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else None


def runs_path(current, **extra):
    query = urlencode({
        "branch": current["head_branch"],
        "event": "push",
        "per_page": 100,
        **extra,
    })
    return f"actions/workflows/{current['workflow_id']}/runs?{query}"


def same_release_workflow(run, current):
    return (
        run["workflow_id"] == current["workflow_id"]
        and run["head_branch"] == current["head_branch"]
        and run["event"] == "push"
    )


def ensure_latest(api, current):
    # Compare workflow runs, not branch HEAD: docs-only pushes may not trigger CI.
    runs = api.request(runs_path(current))["workflow_runs"]
    if any(
        same_release_workflow(run, current)
        and run["run_number"] > current["run_number"]
        for run in runs
    ):
        # Do not report an unpublished run as successful: nx-set-shas would then
        # use it as the next incremental build's baseline and miss its APKs.
        raise RuntimeError("A newer release CI run exists; this run must not publish.")


def cancel_older(api, current):
    older = set()
    for status in ACTIVE_STATUSES:
        page = 1
        while True:
            runs = api.request(runs_path(current, status=status, page=page))["workflow_runs"]
            older.update(
                run["id"] for run in runs
                if same_release_workflow(run, current)
                and run["run_number"] < current["run_number"]
                and run["status"] != "completed"
            )
            if len(runs) < 100:
                break
            page += 1
    for run_id in sorted(older):
        try:
            api.request(f"actions/runs/{run_id}/cancel", method="POST")
            print(f"Requested cancellation of older release run {run_id} (not waiting).")
        except HTTPError as error:
            if error.code != 409:
                raise
            print(f"Run {run_id} is no longer cancellable; continuing.")


def guard(api, run_id, cancel=False):
    current = api.request(f"actions/runs/{run_id}")
    if current["event"] != "push" or current["head_branch"] != "release":
        raise RuntimeError("This guard only supports release push runs.")
    ensure_latest(api, current)
    if cancel:
        cancel_older(api, current)
        ensure_latest(api, current)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cancel-older", action="store_true")
    args = parser.parse_args()
    try:
        guard(GitHubAPI(), int(os.environ["GITHUB_RUN_ID"]), args.cancel_older)
    except (HTTPError, RuntimeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
