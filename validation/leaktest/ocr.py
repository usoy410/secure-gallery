"""PaddleOCR reader for the visible-pixel channel.

PaddleOCR, not Tesseract, judges leaks: the localizer (validation/localize) uses
Tesseract, so no engine grades its own output. Models download once from Hugging Face
into ~/.paddlex (about 13 MB) and are reused offline after that.
"""

from __future__ import annotations

import os
from functools import lru_cache

import numpy as np
from PIL import Image

DETECTION_MODEL = "PP-OCRv5_mobile_det"
RECOGNITION_MODEL = "en_PP-OCRv5_mobile_rec"


@lru_cache(maxsize=1)
def _pipeline():  # type: ignore[no-untyped-def]
    # Skip the slow probe of every model host; the cache or Hugging Face is used.
    os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
    os.environ.setdefault("PADDLE_PDX_MODEL_SOURCE", "huggingface")
    from paddleocr import PaddleOCR

    return PaddleOCR(
        lang="en",
        text_detection_model_name=DETECTION_MODEL,
        text_recognition_model_name=RECOGNITION_MODEL,
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        # Paddle 3.3's oneDNN path crashes on the v5 detector on CPU; plain CPU is fine.
        enable_mkldnn=False,
    )


def paddle_read(crop: Image.Image) -> str:
    """All text PaddleOCR finds in the crop, lines joined by spaces."""
    bgr = np.asarray(crop.convert("RGB"))[:, :, ::-1].copy()
    result = _pipeline().predict(bgr)
    if not result:
        return ""
    return " ".join(result[0].get("rec_texts", []))
