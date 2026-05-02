import json
import os
import sys


def load_client_symbols():
    try:
        from py_clob_client_v2 import (  # type: ignore
            ApiCreds,
            ClobClient,
            OrderArgs,
            OrderType,
            PartialCreateOrderOptions,
            Side,
        )

        return ApiCreds, ClobClient, OrderArgs, OrderType, PartialCreateOrderOptions, Side, True
    except ImportError:
        from py_clob_client.client import ClobClient  # type: ignore
        from py_clob_client.clob_types import ApiCreds, OrderArgs, OrderType  # type: ignore
        from py_clob_client.order_builder.constants import BUY, SELL  # type: ignore

        try:
            from py_clob_client.clob_types import PartialCreateOrderOptions  # type: ignore
        except ImportError:
            PartialCreateOrderOptions = None

        return ApiCreds, ClobClient, OrderArgs, OrderType, PartialCreateOrderOptions, {
            "BUY": BUY,
            "SELL": SELL,
        }, False


def env_value(payload, name_key, required=False):
    env_name = payload.get(name_key)
    if not env_name:
        if required:
            raise RuntimeError(f"{name_key} is required")
        return None
    value = os.environ.get(env_name)
    if required and not value:
        raise RuntimeError(f"Environment variable {env_name} is required")
    return value


def make_client(ClobClient, host, chain_id, private_key, creds, signature_type, funder):
    kwargs = {
        "host": host,
        "chain_id": chain_id,
        "key": private_key,
    }
    if creds is not None:
        kwargs["creds"] = creds
    if signature_type is not None:
        kwargs["signature_type"] = signature_type
    if funder:
        kwargs["funder"] = funder

    try:
        return ClobClient(**kwargs)
    except TypeError:
        kwargs.pop("signature_type", None)
        if not funder:
            kwargs.pop("funder", None)
        return ClobClient(**kwargs)


def api_creds_from_env(ApiCreds, payload):
    api_key = env_value(payload, "apiKeyEnvName")
    api_secret = env_value(payload, "apiSecretEnvName")
    api_passphrase = env_value(payload, "apiPassphraseEnvName")
    if api_key and api_secret and api_passphrase:
        return ApiCreds(
            api_key=api_key,
            api_secret=api_secret,
            api_passphrase=api_passphrase,
        )
    return None


def derive_api_creds(client):
    if hasattr(client, "create_or_derive_api_key"):
        return client.create_or_derive_api_key()
    if hasattr(client, "create_or_derive_api_creds"):
        return client.create_or_derive_api_creds()
    raise RuntimeError("Installed Polymarket client cannot derive API credentials")


def order_type(OrderType, payload):
    name = (payload.get("orderType") or "FAK").upper()
    try:
        return getattr(OrderType, name)
    except AttributeError as exc:
        raise RuntimeError(f"Unsupported Polymarket orderType {name}") from exc


def order_side(side_symbols, payload):
    name = (payload.get("side") or "BUY").upper()
    if isinstance(side_symbols, dict):
        if name in side_symbols:
            return side_symbols[name]
        raise RuntimeError(f"Unsupported Polymarket side {name}")
    try:
        return getattr(side_symbols, name)
    except AttributeError as exc:
        raise RuntimeError(f"Unsupported Polymarket side {name}") from exc


def partial_options(PartialCreateOrderOptions, payload):
    if PartialCreateOrderOptions is None:
        return None
    kwargs = {}
    if payload.get("tickSize"):
        kwargs["tick_size"] = payload.get("tickSize")
    if payload.get("negRisk") is not None:
        kwargs["neg_risk"] = bool(payload.get("negRisk"))
    return PartialCreateOrderOptions(**kwargs) if kwargs else None


def post_order(client, OrderArgs, order_args, order_type_value, options):
    try:
        return client.create_and_post_order(
            order_args=order_args,
            options=options,
            order_type=order_type_value,
        )
    except TypeError:
        try:
            return client.create_and_post_order(
                order_args=order_args,
                order_type=order_type_value,
            )
        except TypeError:
            return client.create_and_post_order(order_args, order_type=order_type_value)


def main():
    payload = json.loads(sys.stdin.read())
    ApiCreds, ClobClient, OrderArgs, OrderType, PartialCreateOrderOptions, side_symbols, _ = load_client_symbols()

    host = payload.get("host") or "https://clob.polymarket.com"
    chain_id = int(payload.get("chainId") or 137)
    signature_type = payload.get("signatureType")
    signature_type = None if signature_type is None else int(signature_type)
    private_key = env_value(payload, "privateKeyEnvName", required=True)
    funder = env_value(payload, "funderAddressEnvName")
    creds = api_creds_from_env(ApiCreds, payload)

    if creds is None:
        client = make_client(ClobClient, host, chain_id, private_key, None, signature_type, funder)
        creds = derive_api_creds(client)

    client = make_client(ClobClient, host, chain_id, private_key, creds, signature_type, funder)
    response = post_order(
        client=client,
        OrderArgs=OrderArgs,
        order_args=OrderArgs(
            token_id=str(payload["tokenId"]),
            price=float(payload["price"]),
            size=float(payload["size"]),
            side=order_side(side_symbols, payload),
        ),
        order_type_value=order_type(OrderType, payload),
        options=partial_options(PartialCreateOrderOptions, payload),
    )
    print(json.dumps(response, default=str, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
