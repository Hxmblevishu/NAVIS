import io
import os
import sys

os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"
os.environ["ULTRALYTICS_CHECK_UPDATES"] = "0"
os.environ["YOLO_VERBOSE"] = "False"

# Ensure repository root is on sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image
from fastapi.testclient import TestClient

from backend.app.main import app
from backend.app.config import get_config

def main():
    print("Creating TestClient without lifespan...", flush=True)
    client = TestClient(app)

    # 1. Test GET /
    print("Testing GET / ...", flush=True)
    res_home = client.get("/")
    print(f"GET /: Status {res_home.status_code}", flush=True)
    assert res_home.status_code == 200
    assert "<html" in res_home.text.lower()

    # Generate a small test image
    img = Image.new("RGB", (320, 320), color="blue")
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    img_bytes = buf.getvalue()

    # 2. Test POST /api/detect without token -> Expect 401
    print("Testing POST /api/detect without token...", flush=True)
    res_no_tok = client.post("/api/detect", files={"image": ("test.jpg", img_bytes, "image/jpeg")})
    print(f"POST /api/detect without token: Status {res_no_tok.status_code}", flush=True)
    assert res_no_tok.status_code == 401

    # 3. Test POST /api/detect with valid token -> Expect 200
    token = get_config().api_token
    print(f"Testing POST /api/detect with valid token ({token[:12]}...)...", flush=True)
    res_tok = client.post(
        "/api/detect",
        headers={"X-API-Token": token},
        files={"image": ("test.jpg", img_bytes, "image/jpeg")}
    )
    print(f"POST /api/detect with token: Status {res_tok.status_code}", flush=True)
    assert res_tok.status_code == 200
    det_data = res_tok.json()
    print(f"  Inference time: {det_data.get('inference_ms')}ms, Detections: {len(det_data.get('detections', []))}", flush=True)

    # 4. Test POST /api/hazard-check -> Expect 200
    print("Testing POST /api/hazard-check ...", flush=True)
    res_hazard = client.post(
        "/api/hazard-check",
        files={"image": ("test.jpg", img_bytes, "image/jpeg")}
    )
    print(f"POST /api/hazard-check: Status {res_hazard.status_code}", flush=True)
    assert res_hazard.status_code == 200
    haz_data = res_hazard.json()
    print(f"  Action: {haz_data['decision']['action']}, Message: {haz_data['decision']['message']}", flush=True)

    print(">>> ALL API ENDPOINT INTEGRATION TESTS SUCCEEDED! <<<", flush=True)

if __name__ == "__main__":
    main()
