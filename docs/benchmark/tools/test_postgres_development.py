import json
import tempfile
import unittest
from pathlib import Path

from develop_postgres_policy import check_collection
from extend_development_dataset import digest
from prepare_dataset import group_id


class PostgresDevelopmentTest(unittest.TestCase):
    def test_rejects_reserved_condition_before_reading_any_transitions(self):
        meta = {"configuration": {"conditions": {"profile": {"name": "slow-dependency"}}}}
        plan = {"postgresVersion": "postgres", "scenarios": ["slow-dependency"], "repeats": 1,
                "properties": {}, "development": {"reservedEvaluationGroup": group_id(meta)}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            protocol = root / "protocol.json"
            protocol.write_text(json.dumps(plan))
            (root / "execution.json").write_text(json.dumps({
                "status": "complete", "postgresStopped": True, "protocolSha256": digest(protocol),
                "postgresVersion": "postgres",
            }))
            (root / "metadata.json").write_text(json.dumps(meta))
            with self.assertRaisesRegex(ValueError, "reserved evaluation group"):
                check_collection(root, protocol, plan)
