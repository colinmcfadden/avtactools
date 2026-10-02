"""A small checker that holds a JSON value to a schema in contracts/openapi.yaml.

It implements the part of OpenAPI 3.0 schemas the spec uses, and refuses any
keyword it does not know. A schema that uses a feature this cannot check must
fail loudly here, not pass silently: a contract test that cannot see a field is
worse than none. (No validator library is a dependency of the backend, and this
is test support only.)
"""

import re
from pathlib import Path

import yaml

SPEC_PATH = Path(__file__).resolve().parents[2] / "contracts" / "openapi.yaml"

# Annotations that say nothing about validity.
_IGNORED = {"description", "example", "title", "default", "format"}

_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "integer": int,
    "number": (int, float),
}


def load_spec():
    return yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))


def _resolve(spec, schema):
    while isinstance(schema, dict) and "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            raise NotImplementedError(f"only local $ref is supported, not {ref!r}")
        node = spec
        for part in ref[2:].split("/"):
            node = node[part]
        schema = node
    return schema


def validate(spec, schema, value, path="$"):
    """The list of problems with ``value`` against ``schema``; empty means valid."""
    schema = _resolve(spec, schema)
    unknown = set(schema) - _IGNORED - {
        "type", "nullable", "enum", "required", "properties", "additionalProperties",
        "items", "pattern", "minLength", "maxLength", "minimum", "maximum",
    }
    if unknown:
        raise NotImplementedError(f"{path}: schema keywords not supported by the checker: {sorted(unknown)}")

    if value is None:
        return [] if schema.get("nullable") else [f"{path}: null is not allowed"]

    expected = schema.get("type")
    if expected:
        python_type = _TYPES[expected]
        # bool is an int in Python; a JSON true is not an integer.
        wrong = not isinstance(value, python_type) or (isinstance(value, bool) and expected in ("integer", "number"))
        if wrong:
            return [f"{path}: expected {expected}, got {type(value).__name__}"]

    problems = []
    if "enum" in schema and value not in schema["enum"]:
        problems.append(f"{path}: {value!r} is not one of {schema['enum']}")
    if isinstance(value, str):
        if "pattern" in schema and not re.search(schema["pattern"], value):
            problems.append(f"{path}: {value!r} does not match {schema['pattern']}")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            problems.append(f"{path}: longer than {schema['maxLength']}")
        if "minLength" in schema and len(value) < schema["minLength"]:
            problems.append(f"{path}: shorter than {schema['minLength']}")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            problems.append(f"{path}: {value} is below {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            problems.append(f"{path}: {value} is above {schema['maximum']}")
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                problems.append(f"{path}: missing required field {name!r}")
        extra = schema.get("additionalProperties", True)
        for name, item in value.items():
            if name in properties:
                problems += validate(spec, properties[name], item, f"{path}.{name}")
            elif extra is False:
                problems.append(f"{path}: unexpected field {name!r}")
            elif isinstance(extra, dict):
                # A map: every extra key's value must match this schema.
                problems += validate(spec, extra, item, f"{path}.{name}")
    if isinstance(value, list) and "items" in schema:
        for index, item in enumerate(value):
            problems += validate(spec, schema["items"], item, f"{path}[{index}]")
    return problems


def response_schema(spec, path, method, status):
    """The JSON schema a route documents for one status code."""
    response = _resolve(spec, spec["paths"][path][method.lower()]["responses"][str(status)])
    return response["content"]["application/json"]["schema"]


def check_response(spec, path, method, status, body):
    """Problems with ``body`` against what the spec says ``method path`` returns for ``status``."""
    return validate(spec, response_schema(spec, path, method, status), body)
