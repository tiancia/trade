import os
from py_clob_client_v2 import ClobClient

host = "https://clob.polymarket.com"
chain_id = 137

client = ClobClient(
    host=host,
    chain_id=chain_id,
    key=os.environ["POLYMARKET_PRIVATE_KEY"],
    signature_type=2,  # 先试 2；如果你是邮箱/Magic 登录，改成 1
    funder=os.environ["POLYMARKET_FUNDER_ADDRESS"],
)

creds = client.create_or_derive_api_key()

print("api_key =", creds.api_key)
print("api_secret =", creds.api_secret)
print("api_passphrase =", creds.api_passphrase)