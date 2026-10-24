"""GitHub 이벤트를 Discord로 전송한다. Python 표준 라이브러리만 사용한다."""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def request_json(url, *, token=None, payload=None):
    headers = {"User-Agent": "BUNCH-GitHub-Notifications"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        # Discord 응답 본문이나 예외 문자열에는 웹훅 URL이 포함될 수 있다.
        raise RuntimeError(f"HTTP request failed (status {error.code}).") from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        raise RuntimeError("HTTP request failed (connection, timeout, or invalid response).") from None


def title_link(item):
    title = " ".join(item["title"].split())
    # 링크가 메시지 경계에서 잘리지 않도록 비정상적으로 긴 제목은 줄인다.
    if len(title) > 500:
        title = title[:499] + "…"
    title = re.sub(r"([\\`*_{}\[\]()<>~|])", r"\\\1", title)
    return f"[#{item['number']} {title}]({item['html_url']})"


def author(item):
    return "@" + ((item.get("user") or {}).get("login") or "ghost")


def event_message(event_name, event):
    action = event.get("action")
    if event_name == "issues" and action == "opened" and "pull_request" not in event["issue"]:
        issue = event["issue"]
        assignees = ", ".join("@" + user["login"] for user in issue.get("assignees", [])) or "미지정"
        return f"🆕 이슈 생성: {title_link(issue)}\n담당자: {assignees} · 작성자: {author(issue)}"
    if event_name == "pull_request_target":
        pr = event["pull_request"]
        if action == "opened":
            return f"🆕 PR opened: {title_link(pr)} by {author(pr)}"
        if action == "closed" and pr.get("merged") is True:
            return f"✅ PR merged: {title_link(pr)} by {author(pr)}"
    return ""


def message_chunks(content):
    """Discord의 2,000자 제한에 맞추며 UTF-16과 줄 경계를 보수적으로 처리한다."""
    chunk = ""
    units = 0
    for line in content.splitlines(keepends=True):
        line_units = len(line.encode("utf-16-le")) // 2
        if chunk and units + line_units > 2000:
            yield chunk
            chunk = ""
            units = 0
        for character in line:
            width = 2 if ord(character) > 0xFFFF else 1
            if units + width > 2000:
                yield chunk
                chunk = ""
                units = 0
            chunk += character
            units += width
    if chunk:
        yield chunk


def send_message(webhook_url, content):
    parsed = urllib.parse.urlsplit(webhook_url)
    if (
        parsed.scheme != "https"
        or parsed.netloc not in {"discord.com", "discordapp.com"}
        or not parsed.path.startswith("/api/webhooks/")
    ):
        raise RuntimeError("DISCORD_WEBHOOK_URL must be a Discord HTTPS webhook URL.")
    query = dict(urllib.parse.parse_qsl(parsed.query))
    query["wait"] = "true"
    url = urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query)))
    for chunk in message_chunks(content):
        request_json(url, payload={
            "username": "GitHub",
            "content": chunk,
            "allowed_mentions": {"parse": []},
        })


def github_get(path):
    return request_json("https://api.github.com" + path, token=os.environ["GH_TOKEN"])


def conflicting_messages(event_name, event):
    repository = os.environ["GITHUB_REPOSITORY"]
    root = f"/repos/{repository}/pulls"
    if event_name == "pull_request_target":
        if event.get("action") == "edited" and "base" not in event.get("changes", {}):
            return ""
        numbers = [event["pull_request"]["number"]]
    elif event_name == "push":
        ref = event.get("ref", "")
        if ref not in {"refs/heads/main", "refs/heads/dev"} or event.get("deleted"):
            return ""
        numbers = []
        page = 1
        while True:
            query = urllib.parse.urlencode({"state": "open", "base": ref.removeprefix("refs/heads/"),
                                            "per_page": 100, "page": page})
            prs = github_get(f"{root}?{query}")
            numbers.extend(pr["number"] for pr in prs)
            if len(prs) < 100:
                break
            page += 1
    else:
        return ""

    messages = []
    pending = numbers
    for attempt in range(5):
        unknown = []
        for number in pending:
            pr = github_get(f"{root}/{number}")
            if pr["state"] != "open":
                continue
            if pr["mergeable"] is None:
                unknown.append(number)
            elif pr["mergeable"] is False:
                messages.append(f"⚠️ PR conflict: {title_link(pr)} by {author(pr)}")
        pending = unknown
        if not pending:
            break
        if attempt < 4:
            time.sleep(6)
    if pending:
        print(f"::warning::{len(pending)} pull request(s) still have unknown mergeability; skipped.")
    return "\n".join(messages)


def main():
    webhook = os.environ.get("DISCORD_WEBHOOK_URL", "")
    if not webhook:
        print("DISCORD_WEBHOOK_URL is not configured; skipping.")
        return 0
    try:
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        event_name = os.environ["GITHUB_EVENT_NAME"]
        if sys.argv[1:] == ["event"]:
            content = event_message(event_name, event)
        elif sys.argv[1:] == ["conflicts"]:
            content = conflicting_messages(event_name, event)
        else:
            raise RuntimeError("Expected mode: event or conflicts.")
        if content:
            send_message(webhook, content)
            print("Discord notification sent.")
        else:
            print("No notification required.")
        return 0
    except RuntimeError as error:
        print(f"::error::{error}")
    except Exception:
        # 예외 traceback에 자격 증명이나 외부 입력을 남기지 않는다.
        print("::error::Notification failed; check event data and configuration.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
