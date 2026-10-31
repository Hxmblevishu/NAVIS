# Custom Trained Model Integration Guide

This directory is the dedicated location for custom-trained computer vision models in NAVIS.

---

## 1. Supported Model Types & Formats

NAVIS's `ObjectDetector` is powered by the **Ultralytics PyTorch** inference runtime. It supports:

1. **PyTorch Weights (`.pt`)**:
   - Models trained with YOLO26, YOLO11, YOLOv10, YOLOv9, YOLOv8, or RT-DETR.
   - Example: `best.pt`, `potholes.pt`, `navigation_hazards.pt`.
2. **ONNX Export (`.onnx`)**:
   - Fast cross-platform CPU/GPU inference.
   - Example: `best.onnx`.
3. **OpenVINO / TensorRT (`.xml` / `.engine`)**:
   - Hardware-accelerated inference for Intel CPUs or NVIDIA GPUs.

---

## 2. Quick Setup: 3 Easy Steps

### Step 1: Drop Your Model File Here
Copy your trained weights file (e.g., `best.pt`) directly into this directory:
```
backend/models/best.pt
```

### Step 2: Configure `.env`
In your root `.env` file, point `YOLO_MODEL` to your weights filename:
```env
YOLO_MODEL=best.pt
```
*(The backend automatically searches `backend/models/`, `backend/`, and the project root).*

### Step 3: (Optional) Register Custom Hazard Classes
If your custom model detects navigation-specific hazards (e.g., potholes, stairs, curb ramps), add them to `CUSTOM_HIGH_PRIORITY_CLASSES` in `.env`:
```env
CUSTOM_HIGH_PRIORITY_CLASSES=pothole,curb,dropoff,pole,stairs_down,wire
```
This ensures these classes immediately trigger high-priority spoken warnings without waiting for multi-frame persistence.

---

## 3. How the Backend Reads Class Names

When Ultralytics loads your `.pt` model, it automatically extracts the label names embedded during training from `model.names`:
```python
# Automatic mapping in backend/app/detection.py
self.classes_map = self._model.names  # e.g., {0: 'pothole', 1: 'curb', 2: 'stairs'}
```
You do not need to manually write label index mappings.

---

## 4. Training Your Own Custom Model (Example)

If you are training a custom YOLO26 model for assistive navigation (e.g., in Google Colab or locally):

```python
from ultralytics import YOLO

# 1. Start from pretrained nano weights
model = YOLO('yolo26n.pt')

# 2. Train on your custom annotated dataset (dataset.yaml)
results = model.train(
    data='path/to/dataset.yaml',
    epochs=50,
    imgsz=640,
    batch=16,
    name='navis_custom_model'
)

# 3. Your trained weights will be saved at:
# runs/detect/navis_custom_model/weights/best.pt
# Copy best.pt into backend/models/best.pt
```

---

## 5. Non-YOLO Custom Models (TensorFlow, PyTorch nn.Module, etc.)

If you have a non-YOLO architecture (e.g., custom PyTorch ResNet, MobileNet, or HuggingFace Vision Transformer):
1. Open [`backend/app/detection.py`](file:///d:/Projects/projects%20of%20codex/NAVIS/backend/app/detection.py).
2. Look at [`DetectorManager.detect()`](file:///d:/Projects/projects%20of%20codex/NAVIS/backend/app/detection.py#L68).
3. Simply replace `results = self._model.predict(...)` with your custom model inference and return a list of `Detection` objects:
   ```python
   Detection(
       label="my_class",
       confidence=0.88,
       bbox=[x1, y1, x2, y2],
       position="center",      # "left", "center", or "right"
       vertical_position="middle",
       area_ratio=0.15
   )
   ```
