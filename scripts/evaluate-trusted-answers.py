"""Aggregate independently reviewed JSONL results; does not call models or infer human labels.

Input rows: id, expectedAnswerable, status, correct (bool, required if answered),
claims=[{type: FACT|INFERENCE, supported|reasonable: bool, labeled: bool}],
citations=[{supportsClaim: bool, locatorCorrect: bool}], latencyMs, optional cost.
Zero denominators are null, never 100%. Missing/duplicate samples fail validation.
"""
import argparse
import json
from pathlib import Path


def ratio(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "value": numerator / denominator if denominator else None}


def summarize(rows):
    seen, counts = set(), dict.fromkeys([
        "total", "answered", "correct", "answerable", "unanswerable", "false_refusal",
        "unsafe_answer", "unavailable", "facts", "supported", "inferences", "reasonable",
        "labeled", "citations", "supporting_citations", "located"], 0)
    latencies, costs = [], []
    for row in rows:
        if not row.get("id") or row["id"] in seen:
            raise ValueError("Every reviewed sample must have a unique id")
        seen.add(row["id"])
        if type(row.get("expectedAnswerable")) is not bool:
            raise ValueError("expectedAnswerable must be independently labeled")
        status = row.get("status")
        if status not in {"ANSWERED", "PARTIAL", "REFUSED", "UNAVAILABLE"}:
            raise ValueError("Invalid answer status")
        answered = status in {"ANSWERED", "PARTIAL"}
        if answered and type(row.get("correct")) is not bool:
            raise ValueError("Answered samples require a human correctness label")
        counts["total"] += 1
        counts["answered"] += answered
        counts["correct"] += answered and row["correct"]
        counts["answerable"] += row["expectedAnswerable"]
        counts["unanswerable"] += not row["expectedAnswerable"]
        counts["false_refusal"] += row["expectedAnswerable"] and status == "REFUSED"
        counts["unsafe_answer"] += not row["expectedAnswerable"] and answered
        counts["unavailable"] += status == "UNAVAILABLE"
        for claim in row.get("claims", []):
            kind = claim.get("type")
            key = "supported" if kind == "FACT" else "reasonable"
            if kind not in {"FACT", "INFERENCE"} or type(claim.get(key)) is not bool:
                raise ValueError("Claims require independent support/reasonableness labels")
            counts["facts" if kind == "FACT" else "inferences"] += 1
            counts[key] += claim[key]
            if kind == "INFERENCE":
                if type(claim.get("labeled")) is not bool:
                    raise ValueError("Inference claims require a display-label assessment")
                counts["labeled"] += claim["labeled"]
        for citation in row.get("citations", []):
            if any(type(citation.get(k)) is not bool for k in ("supportsClaim", "locatorCorrect")):
                raise ValueError("Citations require independent support and location labels")
            counts["citations"] += 1
            counts["supporting_citations"] += citation["supportsClaim"]
            counts["located"] += citation["locatorCorrect"]
        for key, values in (("latencyMs", latencies), ("cost", costs)):
            value = row.get(key)
            if value is not None:
                if type(value) not in (int, float) or value < 0:
                    raise ValueError(key + " must be nonnegative")
                values.append(value)
    pairs = {"answerCoverage": ("answered", "total"), "answerAccuracy": ("correct", "answered"),
             "factSupport": ("supported", "facts"), "inferenceReasonableness": ("reasonable", "inferences"),
             "inferenceLabeling": ("labeled", "inferences"), "citationSupport": ("supporting_citations", "citations"),
             "citationLocation": ("located", "citations"), "unsafeAnswerRate": ("unsafe_answer", "unanswerable"),
             "falseRefusalRate": ("false_refusal", "answerable"), "unavailableRate": ("unavailable", "total")}
    import math
    latencies.sort()
    return {"counts": counts, "metrics": {k: ratio(counts[a], counts[b]) for k, (a, b) in pairs.items()},
            "latencyMs": {"count": len(latencies), **{k: latencies[math.ceil(len(latencies)*p)-1] if latencies else None
                          for k, p in (("p50", .5), ("p95", .95))}},
            "cost": {"measuredSamples": len(costs), "total": sum(costs) if costs else None}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reviewed_results", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.reviewed_results.read_text(encoding="utf-8").splitlines() if line.strip()]
    result = summarize(rows)
    result["source"] = str(args.reviewed_results)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
