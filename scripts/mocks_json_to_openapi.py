#!/usr/bin/env python3
"""Converts a pre-0.2.0 DevView mocks.json into one OpenAPI 3.x spec per API group.

See docs/guides/migrating-to-openapi.md for the full field-by-field mapping and the
sample app's real before/after. This is a one-time upgrade aid, not a permanently
supported tool: it only ever writes new spec files (existing response files are never
moved, renamed, or deleted), and it warns on anything it can't unambiguously translate
rather than guessing.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import re
import sys
import tempfile
from pathlib import Path

# Same right-anchored 3-digit rule as the deleted MockFileNameUtils.parseStatusCode,
# so endpoint ids that themselves contain hyphens still parse correctly.
STATUS_CODE_RE = re.compile(r"-(\d{3})(?:-(.+))?$")

# Same pattern as OpenApiParser.kt's versionPattern.
VERSION_RE = re.compile(r"/(v\d+)(?=/|$)")

KNOWN_GROUP_KEYS = {"id", "name", "endpoints", "environments", "defaultDelayMs"}
KNOWN_ENV_KEYS = {"id", "name", "url", "endpointOverrides", "additionalEndpoints"}
KNOWN_ENDPOINT_KEYS = {"id", "name", "path", "method", "delayMs", "queryParams"}
KNOWN_OVERRIDE_KEYS = {"id", "name", "path", "method", "delayMs", "queryParams"}


def warn(message: str) -> None:
    print(f"WARNING: {message}", file=sys.stderr)


def slugify(title: str) -> str:
    """Mirrors OpenApiParser.slugify (Kotlin) exactly, so this matches the runtime
    spec id the engine will assign from the emitted document's info.title."""
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower().strip())
    return slug.strip("-")


def version_segment(path: str) -> str | None:
    match = VERSION_RE.search(path)
    return match.group(1) if match else None


def pascal_case(text: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in re.split(r"[^a-zA-Z0-9]+", text) if part)


def parse_response_filename(name: str) -> tuple[int, str] | None:
    """`getUser-200.json` -> (200, "default"); `getUser-404-detailed.json` -> (404, "detailed")."""
    stem = name[: -len(".json")] if name.endswith(".json") else name
    match = STATUS_CODE_RE.search(stem)
    if not match:
        return None
    suffix = match.group(2)
    return int(match.group(1)), (suffix if suffix else "default")


def check_unknown_keys(obj: dict, known: set[str], context: str) -> None:
    extra = set(obj.keys()) - known
    if extra:
        warn(f"{context}: unrecognised field(s) {sorted(extra)}, ignored")


def discover_files(
    responses_root: Path, group_id: str, endpoint_id: str, env_id: str | None = None
) -> dict[tuple[int, str], Path]:
    """{(statusCode, exampleName): file path}, empty if the directory doesn't exist."""
    directory = responses_root / group_id / env_id / endpoint_id if env_id else responses_root / group_id / endpoint_id
    result: dict[tuple[int, str], Path] = {}
    if not directory.is_dir():
        return result
    for file in sorted(directory.glob("*.json")):
        parsed = parse_response_filename(file.name)
        if parsed is None:
            warn(f"response file '{file}' doesn't match the "
                 "'{id}-{statusCode}[-{suffix}].json' naming convention, skipped")
            continue
        result[parsed] = file
    return result


def relative_external_value(out_dir: Path, response_path: Path) -> str:
    return Path(os.path.relpath(response_path, out_dir)).as_posix()


def build_responses(files: dict[tuple[int, str], Path], out_dir: Path) -> dict:
    by_status: dict[int, dict[str, Path]] = {}
    for (status, name), path in files.items():
        by_status.setdefault(status, {})[name] = path

    responses: dict = {}
    for status in sorted(by_status):
        examples = by_status[status]
        responses[str(status)] = {
            "description": f"HTTP {status}",
            "content": {
                "application/json": {
                    "examples": {
                        name: {"externalValue": relative_external_value(out_dir, path)}
                        for name, path in sorted(examples.items())
                    }
                }
            },
        }
    return responses


def attach_env_examples(
    operation: dict, env_id: str, files: dict[tuple[int, str], Path], group_id: str, endpoint_id: str
) -> None:
    """Folds environment-tier response files into an unchanged base operation as extra,
    env-prefixed example names — used when files exist for an endpoint that isn't (or is
    only trivially) overridden in this environment, so nothing found on disk is dropped."""
    for (status, name), path in files.items():
        new_name = env_id if name == "default" else f"{env_id}-{name}"
        key = (status, new_name)
        if key in operation["files"]:
            warn(f"group '{group_id}', operation '{endpoint_id}': example '{new_name}' for "
                 f"status {status} already exists, '{path}' skipped")
            continue
        operation["files"][key] = path


def derive_override_operation_id(base_id: str, base_path: str, new_path: str, env_id: str, existing: dict) -> str:
    """`getUserProfile` + `/api/v2/…` -> `getUserProfileV2` (the sample's hand-picked
    name); falls back to the environment id when the override isn't a version bump."""
    new_version = version_segment(new_path)
    candidate = f"{base_id}{new_version.upper()}" if new_version and new_version != version_segment(base_path) \
        else f"{base_id}{pascal_case(env_id)}"

    original, suffix = candidate, 2
    while candidate in existing:
        warn(f"derived operationId '{candidate}' collides with an existing operation, disambiguating")
        candidate = f"{original}{suffix}"
        suffix += 1
    return candidate


def convert_group(group: dict, responses_root: Path, out_dir: Path) -> tuple[str, dict]:
    group_id = group.get("id", "")
    group_name = group.get("name", group_id)
    check_unknown_keys(group, KNOWN_GROUP_KEYS, f"apiGroups[{group_id}]")

    derived_spec_id = slugify(group_name)
    if derived_spec_id != group_id:
        warn(f"group '{group_id}': the engine assigns runtime spec id '{derived_spec_id}' "
             f"(slugified from name '{group_name}'), not '{group_id}' — update any hardcoded "
             "specId references (e.g. DataStore inspection tooling) accordingly")

    endpoints_by_id = {e["id"]: e for e in group.get("endpoints", [])}
    for endpoint in endpoints_by_id.values():
        check_unknown_keys(endpoint, KNOWN_ENDPOINT_KEYS, f"apiGroups[{group_id}].endpoints[{endpoint['id']}]")

    operations: dict[str, dict] = {}
    order: list[str] = []

    def add_operation(op_id, path, method, summary, delay_ms, query_params, files) -> None:
        if op_id in operations:
            warn(f"group '{group_id}': operationId '{op_id}' collides with an existing "
                 "operation, keeping the first and dropping this one")
            return
        operations[op_id] = {
            "path": path, "method": method.upper(), "summary": summary,
            "delay_ms": delay_ms, "query_params": query_params, "files": files,
        }
        order.append(op_id)

    for endpoint_id, endpoint in endpoints_by_id.items():
        add_operation(
            endpoint_id, endpoint["path"], endpoint["method"], endpoint.get("name", endpoint_id),
            endpoint.get("delayMs"), endpoint.get("queryParams"),
            discover_files(responses_root, group_id, endpoint_id),
        )

    servers: list[str] = []
    for env in group.get("environments", []):
        env_id = env.get("id", "")
        check_unknown_keys(env, KNOWN_ENV_KEYS, f"apiGroups[{group_id}].environments[{env_id}]")

        url = env.get("url")
        if url and url not in servers:
            servers.append(url)

        overrides_by_id = {o["id"]: o for o in env.get("endpointOverrides", [])}
        for override_id, override in overrides_by_id.items():
            check_unknown_keys(
                override, KNOWN_OVERRIDE_KEYS,
                f"apiGroups[{group_id}].environments[{env_id}].endpointOverrides[{override_id}]",
            )
            shared = endpoints_by_id.get(override_id)
            if shared is None:
                warn(f"group '{group_id}', environment '{env_id}': endpointOverrides references "
                     f"unknown endpoint '{override_id}', skipped")
                continue

            effective_path = override.get("path") or shared["path"]
            effective_method = override.get("method") or shared["method"]
            effective_name = override.get("name") or shared.get("name", override_id)
            effective_delay = override.get("delayMs", shared.get("delayMs"))
            effective_query = override.get("queryParams", shared.get("queryParams"))
            env_files = discover_files(responses_root, group_id, override_id, env_id)

            if effective_path == shared["path"] and effective_method == shared["method"]:
                if (effective_delay != shared.get("delayMs")
                        or effective_query != shared.get("queryParams")
                        or effective_name != shared.get("name", override_id)):
                    warn(f"group '{group_id}', environment '{env_id}': override for "
                         f"'{override_id}' only changes name/delayMs/queryParams — OpenAPI has "
                         "no per-server operation variant, so the override is dropped "
                         f"(base operation '{override_id}' keeps its own value)")
                if env_files:
                    warn(f"group '{group_id}', environment '{env_id}': '{override_id}' has "
                         "environment-tier response files but its override doesn't change "
                         f"path/method — attached to the base operation as extra examples "
                         f"named '{env_id}[-suffix]'")
                    attach_env_examples(operations[override_id], env_id, env_files, group_id, override_id)
                continue

            derived_id = derive_override_operation_id(override_id, shared["path"], effective_path, env_id, operations)
            shared_files = discover_files(responses_root, group_id, override_id)
            add_operation(derived_id, effective_path, effective_method, effective_name,
                          effective_delay, effective_query, {**shared_files, **env_files})

        for extra in env.get("additionalEndpoints", []):
            check_unknown_keys(
                extra, KNOWN_ENDPOINT_KEYS,
                f"apiGroups[{group_id}].environments[{env_id}].additionalEndpoints[{extra['id']}]",
            )
            extra_id = extra["id"]
            shared_files = discover_files(responses_root, group_id, extra_id)
            env_files = discover_files(responses_root, group_id, extra_id, env_id)
            add_operation(extra_id, extra["path"], extra["method"], extra.get("name", extra_id),
                          extra.get("delayMs"), extra.get("queryParams"), {**shared_files, **env_files})

        for endpoint_id in endpoints_by_id:
            if endpoint_id in overrides_by_id:
                continue
            stray_files = discover_files(responses_root, group_id, endpoint_id, env_id)
            if not stray_files:
                continue
            warn(f"group '{group_id}', environment '{env_id}': found response files for "
                 f"'{endpoint_id}' with no matching override — attached to the base operation "
                 f"as extra examples named '{env_id}[-suffix]'")
            attach_env_examples(operations[endpoint_id], env_id, stray_files, group_id, endpoint_id)

    for op_id in order:
        if not operations[op_id]["files"]:
            warn(f"group '{group_id}', operation '{op_id}': no response files found")

    paths: dict[str, dict] = {}
    for op_id in order:
        op = operations[op_id]
        method_key = op["method"].lower()
        path_item = paths.setdefault(op["path"], {})
        if method_key in path_item:
            warn(f"group '{group_id}': '{op['method']} {op['path']}' is already declared by "
                 f"another operation — '{op_id}' is dropped")
            continue

        operation_obj: dict = {"operationId": op_id, "summary": op["summary"]}
        if op["query_params"]:
            operation_obj["parameters"] = [
                {"name": name, "in": "query", "example": value}
                for name, value in sorted(op["query_params"].items())
            ]
        if op["delay_ms"] is not None:
            operation_obj["x-devview"] = {"delayMs": op["delay_ms"]}
        operation_obj["responses"] = build_responses(op["files"], out_dir)
        path_item[method_key] = operation_obj

    document: dict = {
        "openapi": "3.0.3",
        "info": {"title": group_name, "version": "1.0.0"},
        "servers": [{"url": url} for url in servers],
        "paths": paths,
    }
    default_delay = group.get("defaultDelayMs")
    if default_delay is not None:
        document["x-devview"] = {"delayMs": default_delay}

    return group_id, document


def convert_file(mocks_json: Path, out_dir: Path, responses_root: Path) -> None:
    data = json.loads(mocks_json.read_text(encoding="utf-8"))
    unknown = set(data.keys()) - {"apiGroups"}
    if unknown:
        warn(f"mocks.json: unrecognised top-level key(s) {sorted(unknown)}, ignored")

    out_dir.mkdir(parents=True, exist_ok=True)
    for group in data.get("apiGroups", []):
        group_id, document = convert_group(group, responses_root, out_dir)
        out_path = out_dir / f"{group_id}.json"
        out_path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {out_path}")


def selftest() -> None:
    group = {
        "id": "sample-api",
        "name": "Sample API",
        "defaultDelayMs": 200,
        "endpoints": [
            {"id": "getUserProfile", "name": "Get User Profile",
             "path": "/api/v1/profile/{userId}", "method": "GET"},
            {"id": "updateProfile", "name": "Update Profile",
             "path": "/api/v1/profile", "method": "PUT", "delayMs": 50},
            {"id": "listUsers", "name": "List Users", "path": "/users", "method": "GET"},
        ],
        "environments": [
            {"id": "staging", "name": "Staging", "url": "https://staging.example.com"},
            {
                "id": "prod", "name": "Production", "url": "https://prod.example.com",
                "endpointOverrides": [
                    {"id": "getUserProfile", "path": "/api/v2/profile/{userId}"},
                    {"id": "updateProfile", "delayMs": 999},
                ],
            },
        ],
    }

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        responses_root = root / "responses"
        out_dir = root / "specs"

        def write(rel: str) -> None:
            file = responses_root / rel
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("{}")

        write("sample-api/getUserProfile/getUserProfile-200.json")
        write("sample-api/getUserProfile/getUserProfile-401.json")
        write("sample-api/prod/getUserProfile/getUserProfile-200.json")
        write("sample-api/updateProfile/updateProfile-200.json")
        write("sample-api/listUsers/listUsers-200.json")
        write("sample-api/staging/listUsers/listUsers-200.json")

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            group_id, document = convert_group(group, responses_root, out_dir)
        warnings = stderr.getvalue()

    assert group_id == "sample-api"
    assert document["info"]["title"] == "Sample API"
    assert document["x-devview"] == {"delayMs": 200}
    assert document["servers"] == [
        {"url": "https://staging.example.com"},
        {"url": "https://prod.example.com"},
    ]

    paths = document["paths"]

    v1_op = paths["/api/v1/profile/{userId}"]["get"]
    assert v1_op["operationId"] == "getUserProfile"
    v1_examples = v1_op["responses"]["200"]["content"]["application/json"]["examples"]
    assert set(v1_examples) == {"default"}
    assert "prod" not in v1_examples["default"]["externalValue"]

    v2_op = paths["/api/v2/profile/{userId}"]["get"]
    assert v2_op["operationId"] == "getUserProfileV2"
    assert "401" in v2_op["responses"]  # merged in from the shared tier
    v2_examples = v2_op["responses"]["200"]["content"]["application/json"]["examples"]
    assert set(v2_examples) == {"default"}
    assert "prod" in v2_examples["default"]["externalValue"]  # env tier wins over shared

    update_op = paths["/api/v1/profile"]["put"]
    assert update_op["operationId"] == "updateProfile"
    assert update_op["x-devview"] == {"delayMs": 50}  # prod's delayMs-only override is dropped

    list_op = paths["/users"]["get"]
    list_examples = list_op["responses"]["200"]["content"]["application/json"]["examples"]
    assert set(list_examples) == {"default", "staging"}  # stray env file folded in

    assert "override for 'updateProfile' only changes" in warnings
    assert "found response files for 'listUsers' with no matching override" in warnings

    print("selftest: all assertions passed")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a pre-0.2.0 DevView mocks.json into one OpenAPI 3.x spec per API group."
    )
    parser.add_argument("mocks_json", nargs="?", type=Path, help="Path to the mocks.json to convert")
    parser.add_argument("--out", type=Path, default=None,
                         help="Directory to write the generated spec files (default: <mocks.json dir>/specs)")
    parser.add_argument("--responses", type=Path, default=None,
                         help="Root directory of existing response files (default: <mocks.json dir>/responses)")
    parser.add_argument("--selftest", action="store_true", help="Run the built-in self-check and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        selftest()
        return 0

    if args.mocks_json is None:
        parser.error("mocks_json is required unless --selftest is given")

    base_dir = args.mocks_json.parent
    convert_file(
        mocks_json=args.mocks_json,
        out_dir=args.out or base_dir / "specs",
        responses_root=args.responses or base_dir / "responses",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
