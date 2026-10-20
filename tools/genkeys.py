#!/usr/bin/env python3
"""Usage: python tools/genkeys.py COUNT DAYS DEVICES

Example: python tools/genkeys.py 10 7 1   -> 10 keys, 7 days, 1 device each

How the keys are saved (first one that applies):
  1. GOOGLE_KEY_FILE = path to your Firebase service-account JSON  (recommended)
     needs:  pip install google-auth requests
  2. FB_SECRET = legacy database secret (if your project still has one)
  3. neither -> writes keys_import.json, which you import in the Firebase console
Always needed for 1 and 2: FB_URL = your database URL
"""
import os, sys, json, secrets, urllib.request

count, days, devices = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
alpha = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

def make():
    return "FIRE-" + "-".join("".join(secrets.choice(alpha) for _ in range(4)) for _ in range(4))

keys = {make(): {"days": days, "maxDevices": devices, "banned": False} for _ in range(count)}

def patch(url, token_param):
    req = urllib.request.Request(f"{url}/keys.json?{token_param}", data=json.dumps(keys).encode(),
                                 method="PATCH", headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()

url = os.environ.get("FB_URL", "").rstrip("/")
sa = os.environ.get("GOOGLE_KEY_FILE")
secret = os.environ.get("FB_SECRET")

if url and sa:
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request
    creds = service_account.Credentials.from_service_account_file(
        sa, scopes=["https://www.googleapis.com/auth/firebase.database",
                    "https://www.googleapis.com/auth/userinfo.email"])
    creds.refresh(Request())
    patch(url, "access_token=" + creds.token)
    print("Saved to Firebase.")
elif url and secret:
    patch(url, "auth=" + secret)
    print("Saved to Firebase.")
else:
    with open("keys_import.json", "w") as f:
        json.dump({"keys": keys}, f, indent=2)
    print("Wrote keys_import.json - import it in the Firebase console (Data tab -> menu -> Import JSON).")

print("\n".join(keys))
