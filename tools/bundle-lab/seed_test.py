"""Fixture safety checks; no real database or credentials required."""
import io
import unittest
from unittest.mock import patch

import seed


class Connection:
    def __init__(self):
        self.sent = []
        self.response = io.BytesIO(b"+OK\r\n+OK\r\n")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        pass

    def sendall(self, value):
        self.sent.append(value)

    def recv(self, length):
        return self.response.read(length)


class SeedSafetyTest(unittest.TestCase):
    def test_each_connection_selects_database_before_any_command(self):
        connections = [Connection(), Connection()]
        with patch.object(seed.socket, "create_connection", side_effect=connections):
            seed.redis_command("SET", "session:unit-a", "fake-hash")
            seed.redis_command("SET", "session:unit-b", "fake-hash")
        for connection in connections:
            self.assertEqual(connection.sent[0], b"*2\r\n$6\r\nSELECT\r\n$2\r\n13\r\n")
            self.assertIn(b"SET", connection.sent[1])
            self.assertNotIn(b"FLUSHDB", b"".join(connection.sent))

    def test_seed_refuses_unpiped_execution_before_mutations(self):
        with patch.dict(seed.os.environ, {"BUNDLE_LAB_SEED_PIPE": "0"}), patch.object(seed, "seed_rows") as rows:
            with self.assertRaises(RuntimeError):
                seed.main()
            rows.assert_not_called()


if __name__ == "__main__":
    unittest.main()
