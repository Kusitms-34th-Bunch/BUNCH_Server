import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "discord_notify.py"
SPEC = importlib.util.spec_from_file_location("discord_notify_under_test", MODULE_PATH)
discord_notify = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = discord_notify
assert SPEC.loader is not None
SPEC.loader.exec_module(discord_notify)


WEBHOOK_URL = "https://discord.com/api/webhooks/123/token"
REPOSITORY = "Kusitms-34th-Bunch/BUNCH_Server"


def issue(number=17, title="Fix the issue", login="alice", assignees=()):
    return {
        "number": number,
        "title": title,
        "html_url": f"https://github.com/example/repo/issues/{number}",
        "user": {"login": login},
        "assignees": [{"login": name} for name in assignees],
    }


def pull_request(number=42, *, mergeable=False, state="open", title="Fix the PR"):
    return {
        "number": number,
        "title": title,
        "html_url": f"https://github.com/example/repo/pull/{number}",
        "user": {"login": "contributor"},
        "mergeable": mergeable,
        "state": state,
    }


class DiscordNotifyTests(unittest.TestCase):
    def test_issue_messages_cover_assigned_unassigned_and_multiple(self):
        for assignees, expected in (
            (("bob",), "담당자: @bob"),
            ((), "담당자: 미지정"),
            (("bob", "carol"), "담당자: @bob, @carol"),
        ):
            with self.subTest(assignees=assignees):
                message = discord_notify.event_message(
                    "issues",
                    {"action": "opened", "issue": issue(assignees=assignees)},
                )
                self.assertIn("🆕 이슈 생성:", message)
                self.assertIn(expected, message)
                self.assertIn("작성자: @alice", message)

    def test_draft_pr_open_is_notified(self):
        pr = pull_request(title="Draft PR")
        pr["draft"] = True

        message = discord_notify.event_message(
            "pull_request_target", {"action": "opened", "pull_request": pr}
        )

        self.assertEqual(message, "🆕 PR opened: [#42 Draft PR](https://github.com/example/repo/pull/42) by @contributor")

    def test_closed_pr_is_notified_only_when_merged(self):
        pr = pull_request(title="Release")

        merged = dict(pr, merged=True)
        unmerged = dict(pr, merged=False)

        self.assertIn(
            "✅ PR merged:",
            discord_notify.event_message(
                "pull_request_target", {"action": "closed", "pull_request": merged}
            ),
        )
        self.assertEqual(
            "",
            discord_notify.event_message(
                "pull_request_target", {"action": "closed", "pull_request": unmerged}
            ),
        )

    def test_unsupported_events_are_ignored(self):
        pr = pull_request()
        cases = (
            ("issues", {"action": "closed", "issue": issue()}),
            (
                "issues",
                {"action": "opened", "issue": dict(issue(), pull_request={})},
            ),
            ("pull_request_target", {"action": "synchronize", "pull_request": pr}),
            ("push", {"action": "opened", "pull_request": pr}),
        )

        for event_name, event in cases:
            with self.subTest(event_name=event_name, action=event.get("action")):
                self.assertEqual("", discord_notify.event_message(event_name, event))

    def test_special_titles_and_everyone_remain_message_data(self):
        title = "$(touch /tmp/pwned) ; `rm -rf /` @everyone"
        message = discord_notify.event_message(
            "issues", {"action": "opened", "issue": issue(title=title)}
        )

        self.assertIn("$", message)
        self.assertIn("touch /tmp/pwned", message)
        self.assertIn("rm -rf /", message)
        self.assertIn("@everyone", message)
        self.assertIn(r"\`rm -rf /\`", message)

        with mock.patch.object(discord_notify, "request_json") as request_json:
            discord_notify.send_message(WEBHOOK_URL, message)

        payload = request_json.call_args.kwargs["payload"]
        self.assertIn("@everyone", payload["content"])
        self.assertEqual({"parse": []}, payload["allowed_mentions"])

    def test_title_link_truncates_long_titles_and_preserves_links(self):
        item = issue(number=9, title="x" * 700)
        link = discord_notify.title_link(item)
        url = item["html_url"]

        self.assertIn("x" * 499 + "…", link)
        self.assertNotIn("x" * 500, link)
        self.assertTrue(link.endswith(f"]({url})"))

        newline_title = issue(number=10, title="First\n[bracket]\tSecond")
        self.assertEqual(
            "[#10 First \\[bracket\\] Second](https://github.com/example/repo/issues/10)",
            discord_notify.title_link(newline_title),
        )

    def test_message_chunks_preserve_content_and_fit_utf16_limit(self):
        content = "😀" * 1001 + "\n" + "한" * 2001

        chunks = list(discord_notify.message_chunks(content))

        self.assertGreater(len(chunks), 1)
        self.assertEqual(content, "".join(chunks))
        for chunk in chunks:
            utf16_units = len(chunk.encode("utf-16-le")) // 2
            self.assertLessEqual(utf16_units, 2000)

    def test_send_message_posts_json_with_wait_true_and_mentions_disabled(self):
        webhook = WEBHOOK_URL + "?thread=updates&wait=false"
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"ok": true}'
        with mock.patch.object(
            discord_notify.urllib.request, "urlopen", return_value=response
        ) as urlopen:
            discord_notify.send_message(webhook, "hello @everyone 😀한")

        request = urlopen.call_args.args[0]
        request_url = request.full_url
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(request_url).query)
        self.assertEqual(["updates"], query["thread"])
        self.assertEqual(["true"], query["wait"])
        self.assertEqual("application/json", request.get_header("Content-type"))
        self.assertEqual(
            {
                "username": "GitHub",
                "content": "hello @everyone 😀한",
                "allowed_mentions": {"parse": []},
            },
            json.loads(request.data.decode("utf-8")),
        )
        self.assertIn("😀".encode("utf-8"), request.data)

    def test_send_message_rejects_non_discord_webhook_urls(self):
        invalid_urls = (
            "http://discord.com/api/webhooks/123/token",
            "https://example.com/api/webhooks/123/token",
            "https://discord.com/not-a-webhook/123/token",
        )

        for url in invalid_urls:
            with self.subTest(url=url), mock.patch.object(
                discord_notify, "request_json"
            ) as request_json:
                with self.assertRaisesRegex(RuntimeError, "Discord HTTPS webhook"):
                    discord_notify.send_message(url, "content")
                request_json.assert_not_called()

    def test_http_failures_are_sanitized(self):
        secret_url = WEBHOOK_URL + "?token=secret"
        secret_body = "discord response contains secret body"
        failures = (
            urllib.error.HTTPError(
                secret_url,
                500,
                secret_body,
                hdrs=None,
                fp=io.BytesIO(secret_body.encode()),
            ),
            urllib.error.URLError(f"{secret_url}: {secret_body}"),
        )

        for failure in failures:
            with self.subTest(failure=type(failure).__name__), mock.patch.object(
                discord_notify.urllib.request, "urlopen", side_effect=failure
            ):
                with self.assertRaises(RuntimeError) as raised:
                    discord_notify.request_json(secret_url)
                message = str(raised.exception)
                self.assertNotIn(secret_url, message)
                self.assertNotIn(secret_body, message)
                if isinstance(failure, urllib.error.HTTPError):
                    self.assertEqual("HTTP request failed (status 500).", message)
                else:
                    self.assertEqual(
                        "HTTP request failed (connection, timeout, or invalid response).",
                        message,
                    )
            if isinstance(failure, urllib.error.HTTPError):
                failure.close()

    def test_main_skips_without_a_webhook_secret(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            discord_notify.Path, "read_text"
        ) as read_text, mock.patch.object(discord_notify, "send_message") as send_message, mock.patch.object(
            sys, "argv", ["discord_notify.py", "event"]
        ), contextlib.redirect_stdout(io.StringIO()) as output:
            result = discord_notify.main()

        self.assertEqual(0, result)
        self.assertIn("DISCORD_WEBHOOK_URL is not configured; skipping.", output.getvalue())
        read_text.assert_not_called()
        send_message.assert_not_called()

    def test_main_event_mode_reads_event_and_sends_message(self):
        event = {"action": "opened", "issue": issue(assignees=("bob",))}
        with tempfile.TemporaryDirectory() as temporary_directory:
            event_path = Path(temporary_directory) / "event.json"
            event_path.write_text(json.dumps(event), encoding="utf-8")
            environment = {
                "DISCORD_WEBHOOK_URL": WEBHOOK_URL,
                "GITHUB_EVENT_PATH": str(event_path),
                "GITHUB_EVENT_NAME": "issues",
            }
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                discord_notify, "send_message"
            ) as send_message, mock.patch.object(
                sys, "argv", ["discord_notify.py", "event"]
            ), contextlib.redirect_stdout(io.StringIO()):
                result = discord_notify.main()

        self.assertEqual(0, result)
        send_message.assert_called_once_with(
            WEBHOOK_URL,
            "🆕 이슈 생성: [#17 Fix the issue](https://github.com/example/repo/issues/17)\n"
            "담당자: @bob · 작성자: @alice",
        )

    def test_main_http_failure_is_nonzero_and_sanitized(self):
        event = {"action": "opened", "pull_request": pull_request()}
        secret_url = WEBHOOK_URL + "?token=secret"
        secret_body = "private Discord response body"
        failure = urllib.error.HTTPError(
            secret_url,
            502,
            secret_body,
            hdrs=None,
            fp=io.BytesIO(secret_body.encode()),
        )
        try:
            with tempfile.TemporaryDirectory() as temporary_directory:
                event_path = Path(temporary_directory) / "event.json"
                event_path.write_text(json.dumps(event), encoding="utf-8")
                environment = {
                    "DISCORD_WEBHOOK_URL": WEBHOOK_URL,
                    "GITHUB_EVENT_PATH": str(event_path),
                    "GITHUB_EVENT_NAME": "pull_request_target",
                }
                with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                    discord_notify.urllib.request, "urlopen", side_effect=failure
                ), mock.patch.object(
                    sys, "argv", ["discord_notify.py", "event"]
                ), contextlib.redirect_stdout(io.StringIO()) as output:
                    result = discord_notify.main()
        finally:
            failure.close()

        self.assertEqual(1, result)
        self.assertIn("::error::HTTP request failed (status 502).", output.getvalue())
        self.assertNotIn(secret_url, output.getvalue())
        self.assertNotIn(secret_body, output.getvalue())

    def test_main_conflicts_mode_checks_github_and_sends_message(self):
        event = {"action": "opened", "pull_request": {"number": 42}}
        pr = pull_request(mergeable=False)
        with tempfile.TemporaryDirectory() as temporary_directory:
            event_path = Path(temporary_directory) / "event.json"
            event_path.write_text(json.dumps(event), encoding="utf-8")
            environment = {
                "DISCORD_WEBHOOK_URL": WEBHOOK_URL,
                "GITHUB_EVENT_PATH": str(event_path),
                "GITHUB_EVENT_NAME": "pull_request_target",
                "GITHUB_REPOSITORY": REPOSITORY,
                "GH_TOKEN": "test-token",
            }
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                discord_notify, "github_get", return_value=pr
            ) as github_get, mock.patch.object(
                discord_notify, "send_message"
            ) as send_message, mock.patch.object(
                sys, "argv", ["discord_notify.py", "conflicts"]
            ), contextlib.redirect_stdout(io.StringIO()):
                result = discord_notify.main()

        self.assertEqual(0, result)
        github_get.assert_called_once_with(f"/repos/{REPOSITORY}/pulls/42")
        send_message.assert_called_once_with(
            WEBHOOK_URL,
            "⚠️ PR conflict: [#42 Fix the PR](https://github.com/example/repo/pull/42) by @contributor",
        )

    def test_conflict_polling_retries_unknown_until_mergeability_is_known(self):
        responses = [pull_request(mergeable=None), pull_request(mergeable=False)]
        event = {"action": "opened", "pull_request": {"number": 42}}
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get", side_effect=responses
        ) as github_get, mock.patch.object(discord_notify.time, "sleep") as sleep:
            message = discord_notify.conflicting_messages("pull_request_target", event)

        self.assertIn("PR conflict", message)
        self.assertEqual(2, github_get.call_count)
        sleep.assert_called_once_with(6)

    def test_conflict_polling_skips_persistent_unknown_after_five_attempts(self):
        event = {"action": "opened", "pull_request": {"number": 42}}
        unknown = pull_request(mergeable=None)
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get", return_value=unknown
        ) as github_get, mock.patch.object(discord_notify.time, "sleep") as sleep, contextlib.redirect_stdout(
            io.StringIO()
        ) as output:
            message = discord_notify.conflicting_messages("pull_request_target", event)

        self.assertEqual("", message)
        self.assertEqual(5, github_get.call_count)
        self.assertEqual([mock.call(6)] * 4, sleep.call_args_list)
        self.assertIn("still have unknown mergeability; skipped.", output.getvalue())

    def test_conflicts_include_open_unmerged_prs_and_skip_closed_ones(self):
        event = {"action": "opened", "pull_request": {"number": 1}}
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get", return_value=pull_request(state="closed", mergeable=False)
        ) as github_get:
            self.assertEqual(
                "", discord_notify.conflicting_messages("pull_request_target", event)
            )
            github_get.assert_called_once()

        event["pull_request"]["number"] = 2
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get", return_value=pull_request(number=2, mergeable=False)
        ):
            message = discord_notify.conflicting_messages("pull_request_target", event)

        self.assertIn("#2 Fix the PR", message)

    def test_edited_title_is_ignored_but_base_edits_are_processed(self):
        title_event = {
            "action": "edited",
            "changes": {"title": {"from": "old"}},
            "pull_request": {"number": 42},
        }
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get"
        ) as github_get:
            self.assertEqual(
                "",
                discord_notify.conflicting_messages("pull_request_target", title_event),
            )
            github_get.assert_not_called()

        base_event = {
            "action": "edited",
            "changes": {"base": {"from": {"ref": "main"}}},
            "pull_request": {"number": 42},
        }
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get", return_value=pull_request(mergeable=False)
        ) as github_get:
            message = discord_notify.conflicting_messages(
                "pull_request_target", base_event
            )

        self.assertIn("PR conflict", message)
        github_get.assert_called_once_with(f"/repos/{REPOSITORY}/pulls/42")

    def test_push_main_and_dev_paginate_past_one_hundred_prs(self):
        for branch in ("main", "dev"):
            with self.subTest(branch=branch):
                calls = []

                def github_response(path):
                    calls.append(path)
                    if "?" in path:
                        query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
                        if query["page"] == ["1"]:
                            return [{"number": number} for number in range(1, 101)]
                        self.assertEqual(["2"], query["page"])
                        return [{"number": 101}, {"number": 102}]
                    number = int(path.rsplit("/", 1)[1])
                    return pull_request(
                        number=number,
                        title=f"PR {number}",
                        mergeable=(number != 101),
                    )

                event = {"ref": f"refs/heads/{branch}", "deleted": False}
                with mock.patch.dict(
                    os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True
                ), mock.patch.object(
                    discord_notify, "github_get", side_effect=github_response
                ), mock.patch.object(discord_notify.time, "sleep") as sleep:
                    message = discord_notify.conflicting_messages("push", event)

                listing_calls = [path for path in calls if "?" in path]
                self.assertEqual(2, len(listing_calls))
                for listing_call, page in zip(listing_calls, ("1", "2")):
                    query = urllib.parse.parse_qs(urllib.parse.urlsplit(listing_call).query)
                    self.assertEqual([branch], query["base"])
                    self.assertEqual(["open"], query["state"])
                    self.assertEqual(["100"], query["per_page"])
                    self.assertEqual([page], query["page"])
                self.assertIn("#101 PR 101", message)
                sleep.assert_not_called()

    def test_push_unsupported_and_deleted_refs_are_skipped(self):
        cases = (
            {"ref": "refs/heads/feature", "deleted": False},
            {"ref": "refs/heads/main", "deleted": True},
        )
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY}, clear=True), mock.patch.object(
            discord_notify, "github_get"
        ) as github_get:
            for event in cases:
                with self.subTest(event=event):
                    self.assertEqual("", discord_notify.conflicting_messages("push", event))
            github_get.assert_not_called()


if __name__ == "__main__":
    unittest.main()
