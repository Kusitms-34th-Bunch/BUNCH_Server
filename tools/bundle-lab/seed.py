#!/usr/bin/env python3
"""Create the isolated bundle lab rows and in-memory access sessions.

This command is intended to run in the start.sh stdout pipe. It emits one JSON
fixture to stdout and never writes credentials or tokens to disk.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import uuid


POSTGRES_CONTAINER = "bunch-lab107-postgres"
REDIS_CONTAINER = "bunch-lab107-redis"
POSTGRES_USER = "bunch"
DATABASE = "bunch_bundle_lab107_1129"
REDIS_DATABASE = 13
REFRESH_TTL_SECONDS = 14 * 24 * 60 * 60
ACCESS_TTL_SECONDS = 7 * 24 * 60 * 60


def run_psql(sql: str) -> str:
    command = [
        "docker",
        "exec",
        "-i",
        POSTGRES_CONTAINER,
        "psql",
        "-X",
        "-q",
        "-t",
        "-A",
        "-F",
        "\t",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        POSTGRES_USER,
        "-d",
        DATABASE,
    ]
    result = subprocess.run(
        command,
        input=sql,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("database seed failed")
    return result.stdout.strip()


def redis_command(*parts: str) -> bytes:
    payload = [f"*{len(parts)}\r\n".encode()]
    for part in parts:
        encoded = part.encode()
        payload.append(f"${len(encoded)}\r\n".encode())
        payload.append(encoded)
        payload.append(b"\r\n")

    with socket.create_connection(("127.0.0.1", 26379), timeout=5) as connection:
        # SELECT is connection scoped. Every command opens its own connection.
        connection.sendall(f"*2\r\n$6\r\nSELECT\r\n$2\r\n{REDIS_DATABASE}\r\n".encode())
        if read_redis_response(connection) != b"OK":
            raise RuntimeError("redis database selection failed")
        connection.sendall(b"".join(payload))
        return read_redis_response(connection)


def read_line(connection: socket.socket) -> bytes:
    line = bytearray()
    while not line.endswith(b"\r\n"):
        chunk = connection.recv(1)
        if not chunk:
            raise RuntimeError("redis closed connection")
        line.extend(chunk)
        if len(line) > 4096:
            raise RuntimeError("redis response too large")
    return bytes(line[:-2])


def read_redis_response(connection: socket.socket) -> bytes:
    prefix = connection.recv(1)
    if prefix in (b"+", b":"):
        return read_line(connection)
    if prefix == b"-":
        raise RuntimeError("redis command failed")
    if prefix == b"$":
        length = int(read_line(connection))
        if length < 0:
            return b""
        value = bytearray()
        while len(value) < length:
            chunk = connection.recv(length - len(value))
            if not chunk:
                raise RuntimeError("redis closed connection")
            value.extend(chunk)
        if read_line(connection) != b"":
            raise RuntimeError("redis response framing failed")
        return bytes(value)
    raise RuntimeError("redis response type unsupported")


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def jwt(secret: bytes, member_id: int, session_id: str, token_type: str, ttl: int, role: bool) -> str:
    now = int(time.time())
    header = {"alg": "HS512", "typ": "JWT"}
    claims = {
        "sub": str(member_id),
        "jti": str(uuid.uuid4()),
    }
    if role:
        claims["role"] = "ROLE_USER"
    claims.update({
        "tokenType": token_type,
        "sid": session_id,
        "iat": now,
        "exp": now + ttl,
    })
    encoded_header = b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    encoded_claims = b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    unsigned = f"{encoded_header}.{encoded_claims}".encode("ascii")
    signature = hmac.new(secret, unsigned, hashlib.sha512).digest()
    return f"{encoded_header}.{encoded_claims}.{b64url(signature)}"


def seed_rows() -> tuple[int, int, int, int, int]:
    sql = r"""
BEGIN;
TRUNCATE TABLE
    bundle_edit_leases,
    bundle_comments,
    bundle_participants,
    bundle_photos,
    bundle_requests,
    bundles,
    photo_uploaders,
    photos,
    bunch_invitations,
    bunch_memberships,
    member_consents,
    external_identity,
    bunches,
    image_assets,
    members
RESTART IDENTITY CASCADE;
ALTER TABLE photos ALTER COLUMN id RESTART WITH 101;
ALTER TABLE bundle_photos ALTER COLUMN id RESTART WITH 901;

INSERT INTO members (
    role, signup_status, account_status, nickname, birth_year, gender,
    completed_at, registration_fingerprint
) VALUES (
    'ROLE_USER', 'COMPLETED', 'ACTIVE', 'Bundle Lab A', 1990, 'FEMALE',
    CURRENT_TIMESTAMP, repeat('a', 64)
) RETURNING id \gset member_a_

INSERT INTO members (
    role, signup_status, account_status, nickname, birth_year, gender,
    completed_at, registration_fingerprint
) VALUES (
    'ROLE_USER', 'COMPLETED', 'ACTIVE', 'Bundle Lab B', 1991, 'MALE',
    CURRENT_TIMESTAMP, repeat('b', 64)
) RETURNING id \gset member_b_

INSERT INTO bunches (
    name, introduction, visibility, character_type, created_by,
    creation_request_key, creation_fingerprint, created_at
) VALUES (
    'Bundle Lab', 'Local bundle lifecycle fixture', 'PRIVATE', 'TYPE_1',
    :'member_a_id', '11111111-1111-4111-8111-111111111111', repeat('c', 64), CURRENT_TIMESTAMP
) RETURNING id \gset bunch_

INSERT INTO bunch_memberships (
    bunch_id, member_id, role, status, joined_at, role_granted_at,
    last_used_at, join_requests
) VALUES
    (:'bunch_id', :'member_a_id', 'OWNER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::jsonb),
    (:'bunch_id', :'member_b_id', 'MEMBER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::jsonb);

INSERT INTO image_assets (public_id, url, created_at)
VALUES ('bundle-lab/ready-photo', 'https://example.invalid/bundle-lab/ready-photo.jpg', CURRENT_TIMESTAMP)
RETURNING id \gset asset_

INSERT INTO photos (
    bunch_id, image_asset_id, file_hash, processing_status,
    captured_date, captured_time, date_source, created_at
)
SELECT :'bunch_id', :'asset_id', repeat(md5(g::text), 2), 'READY', CURRENT_DATE,
    TIME '12:00:00', 'USER', CURRENT_TIMESTAMP
FROM generate_series(1, 8) g;

INSERT INTO photo_uploaders (photo_id, member_id, upload_batch_id, uploaded_at)
SELECT p.id, m.id, '22222222-2222-4222-8222-222222222222', CURRENT_TIMESTAMP
FROM photos p CROSS JOIN members m;

INSERT INTO bundles (
    bunch_id, created_by, status, title, visibility, bundle_date,
    revision, title_version, visibility_version, date_version, created_at, updated_at
) VALUES (
    :'bunch_id', :'member_a_id', 'DRAFT', '제주 여행', NULL, NULL,
    12, 3, 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
) RETURNING id \gset bundle_

INSERT INTO bundle_photos (
    bundle_id, photo_id, image_asset_id, position, added_by, added_at,
    caption, caption_version
)
SELECT :'bundle_id', p.id, :'asset_id', (p.id - 101), :'member_a_id', CURRENT_TIMESTAMP,
    '', 4 FROM photos p WHERE p.id <= 106 ORDER BY p.id;

INSERT INTO bundle_participants (bundle_id, member_id, joined_at)
VALUES
    (:'bundle_id', :'member_a_id', CURRENT_TIMESTAMP),
    (:'bundle_id', :'member_b_id', CURRENT_TIMESTAMP);

-- B-only private bunch (ID 2) provides an actual 403 scope for client A.
INSERT INTO bunches (
    name, introduction, visibility, character_type, created_by,
    creation_request_key, creation_fingerprint, created_at
) VALUES (
    'Lab B Only', 'Membership denial fixture', 'PRIVATE', 'TYPE_1',
    :'member_b_id', '44444444-4444-4444-8444-444444444444', repeat('e', 64), CURRENT_TIMESTAMP
) RETURNING id \gset denied_bunch_

INSERT INTO bunch_memberships (
    bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests
) VALUES (
    :'denied_bunch_id', :'member_b_id', 'OWNER', 'ACTIVE', CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::jsonb
);

-- Keep both clients in the tested bunch; the B-only bunch is only a denial fixture.
UPDATE bunch_memberships SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
WHERE bunch_id = :'denied_bunch_id';

SELECT :'member_a_id', :'member_b_id', :'bunch_id', :'bundle_id', 101;
COMMIT;
"""
    values = run_psql(sql).splitlines()
    if len(values) != 1:
        raise RuntimeError("database seed returned an unexpected result")
    ids = values[0].split("\t")
    if len(ids) != 5 or not all(item.isdigit() and int(item) > 0 for item in ids):
        raise RuntimeError("database seed returned invalid ids")
    return tuple(int(item) for item in ids)  # type: ignore[return-value]


def seed_sessions(member_ids: tuple[int, int], secret: bytes) -> dict[str, dict[str, int | str]]:
    clients: dict[str, dict[str, int | str]] = {}
    for name, member_id in zip(("A", "B"), member_ids):
        session_id = str(uuid.uuid4())
        access_token = jwt(secret, member_id, session_id, "access", ACCESS_TTL_SECONDS, True)
        refresh_token = jwt(secret, member_id, session_id, "refresh", REFRESH_TTL_SECONDS, False)
        stored_value = f"{member_id}:{hashlib.sha256(refresh_token.encode('utf-8')).hexdigest()}"
        response = redis_command(
            "SET",
            f"session:{session_id}",
            stored_value,
            "EX",
            str(REFRESH_TTL_SECONDS),
        )
        if response.upper() != b"OK":
            raise RuntimeError("redis session seed failed")
        clients[name] = {"token": access_token, "memberId": member_id}
    return clients


def main() -> int:
    if os.environ.get("BUNDLE_LAB_SEED_PIPE") != "1" or sys.stdout.isatty():
        raise RuntimeError("seed must be piped directly to the local server")
    secret_text = os.environ.get("JWT_SECRET", "")
    if not secret_text:
        raise RuntimeError("JWT secret is missing")
    try:
        secret = base64.b64decode(secret_text, validate=True)
    except (ValueError, TypeError):
        raise RuntimeError("JWT secret is invalid") from None
    if len(secret) < 64:
        raise RuntimeError("JWT secret is too short")

    member_a, member_b, bunch_id, bundle_id, _photo_id = seed_rows()
    clients = seed_sessions((member_a, member_b), secret)
    fixture = {
        "bunchId": bunch_id,
        "bundleId": bundle_id,
        "clients": clients,
    }
    sys.stdout.write(json.dumps(fixture, separators=(",", ":")) + "\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        print("[bundle-lab] fixture seed failed", file=sys.stderr)
        raise SystemExit(1)
