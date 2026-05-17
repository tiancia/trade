import inspect
import json
import os
import sys


def load_client_symbols():
    try:
        from py_clob_client_v2 import (  # type: ignore
            ApiCreds,
            ClobClient,
            MarketOrderArgs,
            OrderArgs,
            OrderType,
            PartialCreateOrderOptions,
            Side,
        )

        return ApiCreds, ClobClient, OrderArgs, MarketOrderArgs, OrderType, PartialCreateOrderOptions, Side, True
    except ImportError:
        from py_clob_client.client import ClobClient  # type: ignore
        from py_clob_client.clob_types import ApiCreds, OrderArgs, OrderType  # type: ignore
        from py_clob_client.order_builder.constants import BUY, SELL  # type: ignore

        try:
            from py_clob_client.clob_types import MarketOrderArgs  # type: ignore
        except ImportError:
            MarketOrderArgs = None

        try:
            from py_clob_client.clob_types import PartialCreateOrderOptions  # type: ignore
        except ImportError:
            PartialCreateOrderOptions = None

        return ApiCreds, ClobClient, OrderArgs, MarketOrderArgs, OrderType, PartialCreateOrderOptions, {
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


def secret_value(payload, value_key, name_key, required=False):
    value = payload.get(value_key)
    if value:
        text = str(value).strip()
        if text:
            return text
    return env_value(payload, name_key, required=required)


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


def api_creds_from_payload(ApiCreds, payload):
    api_key = first_secret_value(payload, "apiKey", "apiKeyEnvName", "POLYMARKET_API_KEY")
    api_secret = first_secret_value(payload, "apiSecret", "apiSecretEnvName", "POLYMARKET_API_SECRET")
    api_passphrase = first_secret_value(payload, "apiPassphrase", "apiPassphraseEnvName", "POLYMARKET_API_PASSPHRASE")
    if api_key and api_secret and api_passphrase:
        return ApiCreds(
            api_key=api_key,
            api_secret=api_secret,
            api_passphrase=api_passphrase,
        )
    return None


def first_secret_value(payload, value_key, *name_keys):
    direct_value = payload.get(value_key)
    if direct_value:
        text = str(direct_value).strip()
        if text:
            return text
    for name_key in name_keys:
        value = env_value(payload, name_key)
        if value:
            return value
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


def accepts_keyword(callable_value, name):
    try:
        signature = inspect.signature(callable_value)
    except (TypeError, ValueError):
        return True
    for parameter in signature.parameters.values():
        if parameter.kind == inspect.Parameter.VAR_KEYWORD:
            return True
    return name in signature.parameters


def can_accept_positional_count(callable_value, count):
    try:
        signature = inspect.signature(callable_value)
    except (TypeError, ValueError):
        return True
    positional_count = 0
    for parameter in signature.parameters.values():
        if parameter.kind == inspect.Parameter.VAR_POSITIONAL:
            return True
        if parameter.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD):
            positional_count += 1
    return positional_count >= count


def create_order(client, order_args, options):
    method = client.create_order
    if accepts_keyword(method, "order_args"):
        kwargs = {"order_args": order_args}
        if options is not None and accepts_keyword(method, "options"):
            kwargs["options"] = options
        return method(**kwargs)
    if options is not None and can_accept_positional_count(method, 2):
        return method(order_args, options)
    return method(order_args)


def create_market_order(client, order_args, options):
    method = client.create_market_order
    if accepts_keyword(method, "order_args"):
        kwargs = {"order_args": order_args}
        if options is not None and accepts_keyword(method, "options"):
            kwargs["options"] = options
        return method(**kwargs)
    if options is not None and can_accept_positional_count(method, 2):
        return method(order_args, options)
    return method(order_args)


def post_signed_order(client, signed_order, order_type_value):
    method = client.post_order
    if accepts_keyword(method, "orderType"):
        return method(signed_order, orderType=order_type_value)
    if accepts_keyword(method, "order_type"):
        return method(signed_order, order_type=order_type_value)
    if can_accept_positional_count(method, 2):
        return method(signed_order, order_type_value)
    return method(signed_order)


def post_order(client, OrderArgs, order_args, order_type_value, options):
    if hasattr(client, "create_order") and hasattr(client, "post_order"):
        signed_order = create_order(client, order_args, options)
        return post_signed_order(client, signed_order, order_type_value)

    method = client.create_and_post_order
    if accepts_keyword(method, "order_args"):
        kwargs = {"order_args": order_args}
        if options is not None and accepts_keyword(method, "options"):
            kwargs["options"] = options
        if accepts_keyword(method, "order_type"):
            kwargs["order_type"] = order_type_value
        elif accepts_keyword(method, "orderType"):
            kwargs["orderType"] = order_type_value
        return method(**kwargs)
    if options is not None and can_accept_positional_count(method, 3):
        return method(order_args, options, order_type_value)
    if can_accept_positional_count(method, 2):
        return method(order_args, order_type_value)
    return method(order_args)


def post_market_order(client, MarketOrderArgs, order_args, order_type_value, options):
    if MarketOrderArgs is None:
        raise RuntimeError("Installed Polymarket client does not support market order arguments")
    if hasattr(client, "create_market_order") and hasattr(client, "post_order"):
        signed_order = create_market_order(client, order_args, options)
        return post_signed_order(client, signed_order, order_type_value)

    if not hasattr(client, "create_and_post_market_order"):
        raise RuntimeError("Installed Polymarket client does not support market order creation")
    method = client.create_and_post_market_order
    if accepts_keyword(method, "order_args"):
        kwargs = {"order_args": order_args}
        if options is not None and accepts_keyword(method, "options"):
            kwargs["options"] = options
        if accepts_keyword(method, "order_type"):
            kwargs["order_type"] = order_type_value
        elif accepts_keyword(method, "orderType"):
            kwargs["orderType"] = order_type_value
        return method(**kwargs)
    if options is not None and can_accept_positional_count(method, 3):
        return method(order_args, options, order_type_value)
    if can_accept_positional_count(method, 2):
        return method(order_args, order_type_value)
    return method(order_args)


def build_market_order_args(MarketOrderArgs, payload, side, order_type_value):
    if MarketOrderArgs is None:
        raise RuntimeError("Installed Polymarket client does not support market order arguments")
    kwargs = {
        "token_id": str(payload["tokenId"]),
        "amount": float(payload["spendUsdc"]),
        "price": float(payload["price"]),
        "side": side,
    }
    if accepts_keyword(MarketOrderArgs, "order_type"):
        kwargs["order_type"] = order_type_value
    elif accepts_keyword(MarketOrderArgs, "orderType"):
        kwargs["orderType"] = order_type_value
    return MarketOrderArgs(**kwargs)


def use_market_buy_payload(payload):
    side = (payload.get("side") or "BUY").upper()
    order_type_name = (payload.get("orderType") or "FAK").upper()
    return side == "BUY" and order_type_name in {"FAK", "FOK"} and payload.get("spendUsdc") not in (None, "")


def main():
    payload = json.loads(sys.stdin.read())
    ApiCreds, ClobClient, OrderArgs, MarketOrderArgs, OrderType, PartialCreateOrderOptions, side_symbols, _ = load_client_symbols()

    host = payload.get("host") or "https://clob.polymarket.com"
    chain_id = int(payload.get("chainId") or 137)
    signature_type = payload.get("signatureType")
    signature_type = None if signature_type is None else int(signature_type)
    private_key = secret_value(payload, "privateKey", "privateKeyEnvName", required=True)
    funder = secret_value(payload, "funderAddress", "funderAddressEnvName")
    creds = api_creds_from_payload(ApiCreds, payload)

    if creds is None:
        client = make_client(ClobClient, host, chain_id, private_key, None, signature_type, funder)
        creds = derive_api_creds(client)

    client = make_client(ClobClient, host, chain_id, private_key, creds, signature_type, funder)
    order_type_value = order_type(OrderType, payload)
    options = partial_options(PartialCreateOrderOptions, payload)
    side = order_side(side_symbols, payload)
    if use_market_buy_payload(payload):
        response = post_market_order(
            client=client,
            MarketOrderArgs=MarketOrderArgs,
            order_args=build_market_order_args(MarketOrderArgs, payload, side, order_type_value),
            order_type_value=order_type_value,
            options=options,
        )
        print(json.dumps(response, default=str, ensure_ascii=False))
        return

    response = post_order(
        client=client,
        OrderArgs=OrderArgs,
        order_args=OrderArgs(
            token_id=str(payload["tokenId"]),
            price=float(payload["price"]),
            size=float(payload["size"]),
            side=side,
        ),
        order_type_value=order_type_value,
        options=options,
    )
    print(json.dumps(response, default=str, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
