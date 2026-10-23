"""Offline regression tests: all SDK and credential inputs are synthetic."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import runpy
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


TOOLS = Path(__file__).resolve().parents[1]


def load_module(filename):
    spec = importlib.util.spec_from_file_location(filename.removesuffix(".py"), TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bridge = load_module("polymarket_place_order.py")
credential_tool = load_module("call_polymarket_order_from_ai.py")


class SigningSafetyTests(unittest.TestCase):
    def test_client_preserves_all_signing_parameters(self):
        factory = Mock(return_value="client")
        result = bridge.make_client(factory, "https://fixture.invalid", 137, "fixture-private", "creds", 2, "funder")
        self.assertEqual(result, "client")
        factory.assert_called_once_with(
            host="https://fixture.invalid", chain_id=137, key="fixture-private", creds="creds",
            signature_type=2, funder="funder",
        )

    def test_constructor_type_error_never_retries_with_a_different_signature(self):
        factory = Mock(side_effect=TypeError("unsupported signature_type"))
        with self.assertRaises(TypeError):
            bridge.make_client(factory, "https://fixture.invalid", 137, "fixture-private", None, 2, "funder")
        self.assertEqual(factory.call_count, 1)

    def test_complete_api_credentials_are_used(self):
        factory = Mock(return_value="creds")
        with patch.dict(os.environ, {}, clear=True):
            result = bridge.api_creds_from_payload(factory, {
                "apiKey": "fixture-key", "apiSecret": "fixture-secret", "apiPassphrase": "fixture-passphrase",
            })
        self.assertEqual(result, "creds")
        factory.assert_called_once_with(
            api_key="fixture-key", api_secret="fixture-secret", api_passphrase="fixture-passphrase",
        )

    def test_partial_api_credentials_cannot_trigger_rederivation(self):
        with patch.dict(os.environ, {}, clear=True):
            for key in ("apiKey", "apiSecret", "apiPassphrase"):
                with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "configured together"):
                    bridge.api_creds_from_payload(Mock(), {key: "fixture-value"})

    def test_absent_api_credentials_keep_existing_derivation_path(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(bridge.api_creds_from_payload(Mock(), {}))


class OrderTypeSafetyTests(unittest.TestCase):
    def test_signed_orders_preserve_camel_case_order_type(self):
        calls = []

        def post_order(signed_order, orderType="GTC"):
            calls.append((signed_order, orderType))
            return "posted"

        self.assertEqual(bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FAK"), "posted")
        self.assertEqual(calls, [("signed", "FAK")])

    def test_signed_orders_preserve_snake_case_order_type(self):
        calls = []

        def post_order(signed_order, *, order_type="GTC"):
            calls.append((signed_order, order_type))

        bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FOK")
        self.assertEqual(calls, [("signed", "FOK")])

    def test_signed_orders_with_no_order_type_are_never_posted(self):
        calls = []

        def post_order(signed_order):
            calls.append(signed_order)

        with self.assertRaisesRegex(RuntimeError, "preserve the requested order type"):
            bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FAK")
        self.assertEqual(calls, [])

    def test_extra_keyword_support_cannot_swallow_the_order_type_spelling(self):
        calls = []

        def post_order(signed_order, *, order_type="GTC", **kwargs):
            calls.append((signed_order, order_type, kwargs))

        bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FAK")
        self.assertEqual(calls, [("signed", "FAK", {})])

    def test_extra_keywords_alone_do_not_prove_order_type_support(self):
        calls = []

        def post_order(signed_order, **kwargs):
            calls.append(signed_order)

        with self.assertRaisesRegex(RuntimeError, "preserve the requested order type"):
            bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FAK")
        self.assertEqual(calls, [])

    def test_an_unrelated_second_parameter_cannot_swallow_the_order_type(self):
        calls = []

        def post_order(signed_order, options=None):
            calls.append((signed_order, options))

        with self.assertRaisesRegex(RuntimeError, "preserve the requested order type"):
            bridge.post_signed_order(SimpleNamespace(post_order=post_order), "signed", "FAK")
        self.assertEqual(calls, [])

    def test_combined_limit_order_keeps_order_type(self):
        calls = []

        def create_and_post_order(order_args, *, order_type="GTC", options=None):
            calls.append((order_args, order_type, options))

        bridge.post_order(SimpleNamespace(create_and_post_order=create_and_post_order), None, "args", "FAK", "options")
        self.assertEqual(calls, [("args", "FAK", "options")])

    def test_combined_market_order_keeps_order_type(self):
        calls = []

        def create_and_post_market_order(order_args, *, orderType="GTC", options=None):
            calls.append((order_args, orderType, options))

        bridge.post_market_order(SimpleNamespace(create_and_post_market_order=create_and_post_market_order), object, "args", "FOK", "options")
        self.assertEqual(calls, [("args", "FOK", "options")])

    def test_combined_order_cannot_swallow_order_type_in_extra_keywords(self):
        calls = []

        def create_and_post_order(order_args, *, orderType="GTC", **kwargs):
            calls.append((order_args, orderType, kwargs))

        bridge.post_order(SimpleNamespace(create_and_post_order=create_and_post_order), None, "args", "FAK", None)
        self.assertEqual(calls, [("args", "FAK", {})])

    def test_combined_limit_order_without_order_type_is_never_called(self):
        calls = []

        def create_and_post_order(order_args, options=None):
            calls.append(order_args)

        with self.assertRaisesRegex(RuntimeError, "preserve the requested order type"):
            bridge.post_order(SimpleNamespace(create_and_post_order=create_and_post_order), None, "args", "FAK", None)
        self.assertEqual(calls, [])

    def test_combined_market_order_without_order_type_is_never_called(self):
        calls = []

        def create_and_post_market_order(args, options=None):
            calls.append(args)

        with self.assertRaisesRegex(RuntimeError, "preserve the requested order type"):
            bridge.post_market_order(SimpleNamespace(create_and_post_market_order=create_and_post_market_order), object, "args", "FOK", None)
        self.assertEqual(calls, [])


class CredentialToolSafetyTests(unittest.TestCase):
    def run_tool(self, argv, factory=None, environment=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        factory = factory or Mock()
        with patch.dict(os.environ, environment or {}, clear=True), patch.dict(
            sys.modules, {"py_clob_client_v2": SimpleNamespace(ClobClient=factory)},
        ), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = credential_tool.main(argv)
        return result, stdout.getvalue() + stderr.getvalue(), factory

    def test_import_does_not_construct_a_client(self):
        factory = Mock(side_effect=AssertionError("network is forbidden"))
        with patch.dict(os.environ, {}, clear=True), patch.dict(
            sys.modules, {"py_clob_client_v2": SimpleNamespace(ClobClient=factory)},
        ):
            load_module("call_polymarket_order_from_ai.py")
        factory.assert_not_called()

    def test_default_invocation_is_offline_without_sdk_or_credentials(self):
        result, output, factory = self.run_tool([])
        self.assertEqual(result, 0)
        self.assertIn("No network request made", output)
        factory.assert_not_called()

    def test_derivation_requires_both_environment_values(self):
        result, output, factory = self.run_tool(["--derive-api-credentials"])
        self.assertEqual(result, 2)
        factory.assert_not_called()
        self.assertIn("POLYMARKET_PRIVATE_KEY", output)

    def test_derivation_does_not_print_credentials_by_default(self):
        creds = SimpleNamespace(api_key="fixture-api-key", api_secret="fixture-api-secret", api_passphrase="fixture-passphrase")
        client = Mock()
        client.create_or_derive_api_key.return_value = creds
        result, output, factory = self.run_tool(["--derive-api-credentials"], Mock(return_value=client), {
            "POLYMARKET_PRIVATE_KEY": "fixture-private-key", "POLYMARKET_FUNDER_ADDRESS": "fixture-funder",
        })
        self.assertEqual(result, 0)
        self.assertIn("Values hidden", output)
        for value in vars(creds).values():
            self.assertNotIn(value, output)
        self.assertEqual(factory.call_count, 1)
        client.create_or_derive_api_key.assert_called_once_with()
        client.post_order.assert_not_called()

    def test_explicit_show_secrets_can_display_derived_values(self):
        client = Mock()
        client.create_or_derive_api_key.return_value = SimpleNamespace(
            api_key="fixture-api-key", api_secret="fixture-api-secret", api_passphrase="fixture-passphrase",
        )
        result, output, _ = self.run_tool(["--derive-api-credentials", "--show-secrets"], Mock(return_value=client), {
            "POLYMARKET_PRIVATE_KEY": "fixture-private-key", "POLYMARKET_FUNDER_ADDRESS": "fixture-funder",
        })
        self.assertEqual(result, 0)
        self.assertIn("fixture-api-secret", output)
        self.assertNotIn("fixture-private-key", output)

    def test_sdk_error_does_not_expose_request_secrets(self):
        result, output, _ = self.run_tool(["--derive-api-credentials"], Mock(side_effect=RuntimeError("fixture-secret-leak")), {
            "POLYMARKET_PRIVATE_KEY": "fixture-private-key", "POLYMARKET_FUNDER_ADDRESS": "fixture-funder",
        })
        self.assertEqual(result, 1)
        self.assertNotIn("fixture-secret-leak", output)

    def test_show_secrets_alone_cannot_enable_network_access(self):
        factory = Mock()
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stopped:
            self.run_tool(["--show-secrets"], factory)
        self.assertEqual(stopped.exception.code, 2)
        factory.assert_not_called()


class BridgeErrorSafetyTests(unittest.TestCase):
    def test_top_level_sdk_error_does_not_expose_request_headers(self):
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), patch.dict(
            sys.modules, {"py_clob_client_v2": None, "py_clob_client": None},
        ), patch("json.loads", side_effect=RuntimeError("fixture-header-secret")), patch(
            "sys.stdin", io.StringIO("{}"),
        ), contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as stopped:
            runpy.run_path(str(TOOLS / "polymarket_place_order.py"), run_name="__main__")
        self.assertEqual(stopped.exception.code, 1)
        self.assertIn("RuntimeError", stderr.getvalue())
        self.assertNotIn("fixture-header-secret", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
