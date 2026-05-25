import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STORE_PATH = ROOT / "logs" / "manual-runs" / "store_dump.json"


def main() -> None:
	store_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_STORE_PATH
	data = json.loads(store_path.read_text(encoding="utf-8-sig"), strict=False)
	current_state = data.get("currentState") or {}
	messages = data.get("messages") or []

	print("STG: " + str(current_state.get("stage")))
	print("CAN: " + str(data.get("activeCandidateId")))
	print("PRE: " + str(data.get("currentExecutionPreparation") is not None))
	print("EVE: " + str(len(data.get("executionEvents") or [])))
	print("MSG: " + ((messages[-1].get("content", "")[:100]) if messages else ""))


if __name__ == "__main__":
	main()