#!/usr/bin/env python3
"""Submit a VERA viewshed job, show progress, and download its result."""

import argparse
import json
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


def request_json(url, method="GET", payload=None):
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    request = Request(url, data=data, headers=headers, method=method)
    with urlopen(request) as response:
        return json.load(response)


def download(url, output):
    request = Request(url, headers={"Accept": "application/octet-stream"})
    with urlopen(request) as response, output.open("wb") as destination:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            destination.write(chunk)


def normalize_server_url(server):
    """Return a URL suitable for urllib when given a host shorthand."""
    server = server.strip()
    if "://" not in server:
        server = "http://" + server
    return server.rstrip("/") + "/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default="http://localhost:7070",
                        help="VERA server URL (default: %(default)s)")
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--agl", type=float, default=10.0,
                        help="Observer height above ground in meters (default: %(default)s)")
    parser.add_argument("--radius-meters", type=float, default=40000.0,
                        help="Viewshed radius in meters (default: %(default)s)")
    parser.add_argument("--zoom", type=int, default=12)
    parser.add_argument("--angle-bins", type=int, default=1440)
    parser.add_argument("--format", choices=("png", "kmz"), default="png")
    parser.add_argument("--name", help="Optional KMZ name and download filename")
    parser.add_argument("--source", choices=("terrarium", "copernicus"), default="terrarium")
    parser.add_argument("--cache", help="Optional terrain cache directory on the server")
    parser.add_argument("--output", type=Path,
                        help="Output file (default: viewshed.<format>)")
    parser.add_argument("--interval", type=float, default=2.0,
                        help="Seconds between status requests (default: %(default)s)")
    args = parser.parse_args()

    server = normalize_server_url(args.server)
    payload = {
        "lat": args.lat,
        "lon": args.lon,
        "agl": args.agl,
        "radiusMeters": args.radius_meters,
        "zoom": args.zoom,
        "angleBins": args.angle_bins,
        "format": args.format,
        "source": args.source,
    }
    if args.name:
        payload["name"] = args.name
    if args.cache:
        payload["cache"] = args.cache

    try:
        job = request_json(urljoin(server, "api/viewsheds"), "POST", payload)
        status_url = urljoin(server, job["statusUrl"])
        result_url = urljoin(server, job["resultUrl"])
        print(f"Submitted job {job['id']}")

        while job["status"] in ("queued", "running"):
            print(
                f"{job['phase']}: {job['current']}/{job['total']} "
                f"({job['percent']:.2f}%)",
                flush=True,
            )
            time.sleep(args.interval)
            job = request_json(status_url)

        if job["status"] != "completed":
            raise RuntimeError(job.get("error", f"Job ended with status {job['status']}"))

        default_name = args.name or "viewshed"
        if args.format == "kmz" and not default_name.lower().endswith(".kmz"):
            default_name += ".kmz"
        output = args.output or Path(default_name if args.format == "kmz" else f"{default_name}.png")
        download(result_url, output)
        print(f"Saved {output}")
    except (HTTPError, URLError, KeyError, json.JSONDecodeError, RuntimeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
