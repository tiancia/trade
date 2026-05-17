import argparse
import json
import os
import subprocess
import sys
from decimal import Decimal, InvalidOperation, ROUND_DOWN
from pathlib import Path


# Fill these values before running this debug harness.
# Existing environment variables are kept when the value here is left blank.
ENV_VALUES = {
    "POLYMARKET_PRIVATE_KEY": "0x6b0cd2d7e7ca93b69e8a9d9c5174fda85a5de07109967ed51eea0822c7f0b257",
    "POLYMARKET_API_KEY": "8df70858-230c-c145-770d-0e7fd091adb3",
    "POLYMARKET_API_SECRET": "ZGomTHa6pPvfcRb217MOIc8t4hO3qvyTsirPdwnkOMM=",
    "POLYMARKET_API_PASSPHRASE": "870e5bdf43459da618810acf312361a98d803452c83fddc0efb37353f2913457",
    "POLYMARKET_FUNDER_ADDRESS": "0x4fd277efaba9cf8af720849606649cf01cdac510",
}

PAYLOAD = {
    "host": "https://clob.polymarket.com",
    "chainId": 137,
    "signatureType": 1,
    "orderType": "FAK",
    "privateKeyEnvName": "POLYMARKET_PRIVATE_KEY",
    "apiKeyEnvName": "POLYMARKET_API_KEY",
    "apiSecretEnvName": "POLYMARKET_API_SECRET",
    "apiPassphraseEnvName": "POLYMARKET_API_PASSPHRASE",
    "funderAddressEnvName": "POLYMARKET_FUNDER_ADDRESS",
    "tokenId": "69944000405057355497343972061914269596481459431953471217189377963336927362514",
    "side": "BUY",
    "price": "0.01",
    "size": "",
    "spendUsdc": "0.5",
    "tickSize": "0.01",
    "negRisk": None,
    "marketSlug": "will-the-republican-party-win-the-al-03-house-seat",
    "outcome": "Yes",
}

# If size is blank and spendUsdc + price are set, the harness computes size
# using the same 4-decimal, round-down behavior as the Java order executor.
AUTO_SIZE_FROM_SPEND = True

PYTHON_COMMAND = "python"
ORDER_SCRIPT = Path(__file__).resolve().with_name("polymarket_place_order.py")
TIMEOUT_SECONDS = 60


def main():
    args = parse_args()
    script_path = (args.script or ORDER_SCRIPT).resolve()
    payload = build_payload(PAYLOAD)
    env = build_environment(os.environ.copy(), ENV_VALUES)

    errors = validation_errors(payload, env, script_path)
    if errors:
        print("Missing required debug values:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 2

    payload_json = json.dumps(payload, ensure_ascii=False)
    print_debug_context(args.python, script_path, payload, env)

    if args.print_only:
        print(payload_json)
        return 0

    if not args.confirm_live_order:
        print(
            "Refusing to place a live Polymarket order. "
            "Re-run with --confirm-live-order after checking the payload.",
            file=sys.stderr,
        )
        return 2

    try:
        completed = subprocess.run(
            [args.python, str(script_path)],
            input=payload_json,
            text=True,
            capture_output=True,
            timeout=args.timeout,
            env=env,
            cwd=str(script_path.parent),
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        print(f"Order script timed out after {args.timeout}s", file=sys.stderr)
        if exc.stdout:
            print_section("stdout", exc.stdout)
        if exc.stderr:
            print_section("stderr", exc.stderr)
        return 124

    print_section("stdout", completed.stdout)
    print_section("stderr", completed.stderr)
    print(f"exitCode={completed.returncode}")
    return completed.returncode


def parse_args():
    parser = argparse.ArgumentParser(
        description="Debug harness for scripts/polymarket_place_order.py"
    )
    parser.add_argument(
        "--confirm-live-order",
        action="store_true",
        help="Actually call the order script. This can place a live order.",
    )
    parser.add_argument(
        "--print-only",
        action="store_true",
        help="Print the generated payload without calling the order script.",
    )
    parser.add_argument(
        "--python",
        default=PYTHON_COMMAND,
        help="Python command used to run polymarket_place_order.py.",
    )
    parser.add_argument(
        "--script",
        type=Path,
        default=None,
        help="Path to polymarket_place_order.py.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=TIMEOUT_SECONDS,
        help="Timeout for the order script in seconds.",
    )
    return parser.parse_args()


def build_payload(template):
    payload = dict(template)
    if AUTO_SIZE_FROM_SPEND and is_blank(payload.get("size")):
        payload["size"] = computed_size(payload.get("spendUsdc"), payload.get("price"))
    return {key: value for key, value in payload.items() if not is_blank(value)}


def build_environment(environment, values):
    for name, value in values.items():
        if not is_blank(value):
            environment[name] = str(value).strip()
    return environment


def validation_errors(payload, env, script_path):
    errors = []
    if not script_path.is_file():
        errors.append(f"order script not found: {script_path}")
    for field in ("tokenId", "price", "size"):
        if is_blank(payload.get(field)):
            errors.append(f"PAYLOAD['{field}']")
    private_key_env_name = payload.get("privateKeyEnvName")
    if is_blank(private_key_env_name):
        errors.append("PAYLOAD['privateKeyEnvName']")
    elif is_blank(env.get(str(private_key_env_name))):
        errors.append(
            f"ENV_VALUES['{private_key_env_name}'] or environment variable {private_key_env_name}"
        )
    return errors


def computed_size(spend_usdc, price):
    if is_blank(spend_usdc) or is_blank(price):
        return ""
    try:
        spend = Decimal(str(spend_usdc))
        price_value = Decimal(str(price))
    except InvalidOperation:
        return ""
    if price_value <= 0:
        return ""
    size = (spend / price_value).quantize(Decimal("0.0001"), rounding=ROUND_DOWN)
    return decimal_to_plain(size)


def decimal_to_plain(value):
    text = format(value.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def print_debug_context(python_command, script_path, payload, env):
    secret_status = {
        name: ("set" if not is_blank(env.get(name)) else "missing")
        for name in ENV_VALUES
    }
    print(f"python={python_command}")
    print(f"script={script_path}")
    print(f"secretEnvStatus={json.dumps(secret_status, ensure_ascii=False)}")
    print("payload=" + json.dumps(payload, ensure_ascii=False, indent=2))


def print_section(name, value):
    print(f"--- {name} ---")
    if value:
        print(value.rstrip())


def is_blank(value):
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    return False


if __name__ == "__main__":
    sys.exit(main())
