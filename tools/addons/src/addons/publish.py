import os
from pathlib import Path

import boto3

SHORT = "public, max-age=60"

CONTENT_TYPES = {
    ".html": "text/html",
    ".json": "application/json",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".cgp": "application/octet-stream",
    ".gz": "application/gzip",
}
ATTACHMENTS = {".cgp", ".gz"}


def headers_for(key: str) -> dict:
    suffix = Path(key).suffix
    headers = {
        "ContentType": CONTENT_TYPES.get(suffix, "application/octet-stream"),
        "CacheControl": SHORT,
    }
    if suffix in ATTACHMENTS:
        headers["ContentDisposition"] = f'attachment; filename="{Path(key).name}"'
    return headers


def put(client, bucket: str, key: str, path: Path) -> None:
    client.put_object(Bucket=bucket, Key=key, Body=path.read_bytes(),
                      **headers_for(key))


def publish(client, bucket: str, objects: list[tuple[str, Path]],
            catalog: tuple[str, Path]) -> None:
    for key, path in objects:
        put(client, bucket, key, path)
    put(client, bucket, catalog[0], catalog[1])


def bucket_from_env() -> str:
    return os.environ.get("R2_BUCKET", "addons")


def client_from_env():
    account = os.environ["R2_ACCOUNT_ID"]
    return boto3.client(
        "s3",
        endpoint_url=f"https://{account}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
