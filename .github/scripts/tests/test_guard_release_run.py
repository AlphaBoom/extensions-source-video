import importlib.util
import io
from contextlib import redirect_stdout
from pathlib import Path
import unittest
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit


spec = importlib.util.spec_from_file_location(
    "guard_release_run", Path(__file__).parents[1] / "guard-release-run.py"
)
guard_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard_module)


def run(number, **overrides):
    return {
        "id": number,
        "run_number": number,
        "workflow_id": 7,
        "head_branch": "release",
        "event": "push",
        "status": "in_progress",
        **overrides,
    }


class FakeAPI:
    def __init__(self, current, runs):
        self.current = current
        self.runs = runs
        self.cancelled = []
        self.calls = []
        self.cancel_error = None
        self.latest_checks = 0
        self.newer_after_cancel = False

    def request(self, path, method="GET"):
        self.calls.append((path, method))
        if method == "POST":
            if self.cancel_error:
                raise HTTPError(path, self.cancel_error, "cancel error", {}, None)
            self.cancelled.append(int(path.split("/")[2]))
            return None
        if path == f"actions/runs/{self.current['id']}":
            return self.current
        query = parse_qs(urlsplit(path).query)
        status = query.get("status", [None])[0]
        if status is None:
            self.latest_checks += 1
            if self.newer_after_cancel and self.latest_checks > 1:
                return {"workflow_runs": [run(self.current["run_number"] + 1)]}
        runs = [r for r in self.runs if status is None or r["status"] == status]
        page = int(query.get("page", ["1"])[0])
        return {"workflow_runs": runs[(page - 1) * 100:page * 100]}


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(redirect_stdout(io.StringIO()))

    def test_cancels_only_older_active_runs_of_same_release_workflow(self):
        current = run(10)
        api = FakeAPI(current, [
            run(1), run(2, status="queued"), run(3, status="pending"),
            run(4, status="waiting"), run(5, status="requested"),
            run(6, status="completed"), run(7, workflow_id=99),
            run(8, event="pull_request"), run(9, head_branch="other"), current,
        ])
        guard_module.guard(api, 10, cancel=True)
        self.assertEqual(api.cancelled, [1, 2, 3, 4, 5])
        # Cancellation does not wait/poll for those runs to complete.
        self.assertFalse(any(p == "actions/runs/1" for p, _ in api.calls))

    def test_stale_run_fails_before_it_can_cancel_anything(self):
        api = FakeAPI(run(10), [run(9), run(11)])
        with self.assertRaisesRegex(RuntimeError, "newer release"):
            guard_module.guard(api, 10, cancel=True)
        self.assertEqual(api.cancelled, [])

    def test_publication_rejects_old_run_even_if_newer_run_finished(self):
        api = FakeAPI(run(10), [run(11, status="completed")])
        with self.assertRaises(RuntimeError):
            guard_module.guard(api, 10)

    def test_latest_run_can_publish_without_cancelling(self):
        api = FakeAPI(run(10), [run(9), run(10)])
        guard_module.guard(api, 10)
        self.assertEqual(api.cancelled, [])

    def test_docs_only_branch_update_does_not_supersede_build(self):
        api = FakeAPI(run(10, head_sha="before-docs"), [run(10)])
        guard_module.guard(api, 10)
        self.assertTrue(all(p.startswith("actions/") for p, _ in api.calls))

    def test_non_release_or_pr_run_is_rejected(self):
        for current in [run(10, head_branch="main"), run(10, event="pull_request")]:
            with self.subTest(current=current), self.assertRaises(RuntimeError):
                guard_module.guard(FakeAPI(current, [current]), 10, cancel=True)

    def test_cancellation_handles_pagination(self):
        api = FakeAPI(run(102), [run(n) for n in range(1, 103)])
        guard_module.guard(api, 102, cancel=True)
        self.assertEqual(api.cancelled, list(range(1, 102)))

    def test_finished_during_cancel_is_harmless(self):
        api = FakeAPI(run(10), [run(9), run(10)])
        api.cancel_error = 409
        guard_module.guard(api, 10, cancel=True)

    def test_permission_errors_are_not_silenced(self):
        api = FakeAPI(run(10), [run(9), run(10)])
        api.cancel_error = 403
        with self.assertRaises(HTTPError):
            guard_module.guard(api, 10, cancel=True)

    def test_new_push_during_cancellation_prevents_successful_baseline(self):
        api = FakeAPI(run(10), [run(9), run(10)])
        api.newer_after_cancel = True
        with self.assertRaises(RuntimeError):
            guard_module.guard(api, 10, cancel=True)


if __name__ == "__main__":
    unittest.main()
