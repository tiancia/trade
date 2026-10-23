"""Explicit, local CLI for deriving Polymarket API credentials; never places orders."""

import argparse
import os
import sys


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--derive-api-credentials",
        action="store_true",
        help="Contact Polymarket to create or derive API credentials.",
    )
    parser.add_argument(
        "--show-secrets",
        action="store_true",
        help="Explicitly print the derived credentials in this private terminal.",
    )
    args = parser.parse_args(argv)
    if args.show_secrets and not args.derive_api_credentials:
        parser.error("--show-secrets requires --derive-api-credentials")
    return args


def main(argv=None):
    args = parse_args(argv)
    if not args.derive_api_credentials:
        print("No network request made. Use --derive-api-credentials to derive credentials.")
        return 0

    required_names = ("POLYMARKET_PRIVATE_KEY", "POLYMARKET_FUNDER_ADDRESS")
    if any(not os.environ.get(name, "").strip() for name in required_names):
        print("Set POLYMARKET_PRIVATE_KEY and POLYMARKET_FUNDER_ADDRESS first.", file=sys.stderr)
        return 2

    try:
        # Import only after explicit opt-in; importing this module is offline.
        from py_clob_client_v2 import ClobClient

        client = ClobClient(
            host="https://clob.polymarket.com",
            chain_id=137,
            key=os.environ["POLYMARKET_PRIVATE_KEY"],
            signature_type=2,
            funder=os.environ["POLYMARKET_FUNDER_ADDRESS"],
        )
        creds = client.create_or_derive_api_key()
    except Exception:
        # SDK errors can contain request headers or credential material.
        print("Credential derivation failed. Check the local settings and SDK connection.", file=sys.stderr)
        return 1

    if args.show_secrets:
        print("api_key =", creds.api_key)
        print("api_secret =", creds.api_secret)
        print("api_passphrase =", creds.api_passphrase)
    else:
        print("API credentials are ready. Values hidden; use --show-secrets only in a private terminal.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
