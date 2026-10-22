"""Unit tests for YOLO pose format converter and metadata generation without torch dependency."""

import json
from pathlib import Path

import pytest
import yaml

from ml.converter import convert_d7_dataset
from ml.metadata import (
    DEFAULT_DOCUMENT_CLASSES,
    DEFAULT_KEYPOINT_ORDER,
    build_model_metadata,
    load_model_metadata,
    save_model_metadata,
)
from ml.pose_format import (
    audit_roboflow_license,
    bounding_box_from_corners,
    format_yolo_pose_line,
    ordered_corner_keypoints,
)


def test_bounding_box_from_corners():
    corners = [[100, 100], [500, 100], [500, 300], [100, 300]]
    img_size = [1000, 500]

    cx, cy, w, h = bounding_box_from_corners(corners, img_size)
    assert pytest.approx(cx) == 0.3
    assert pytest.approx(cy) == 0.4
    assert pytest.approx(w) == 0.4
    assert pytest.approx(h) == 0.4


def test_ordered_corner_keypoints_dim2_and_dim3():
    corners = [[0, 0], [100, 0], [100, 50], [0, 50]]
    img_size = [100, 100]

    kpts2 = ordered_corner_keypoints(corners, img_size, kpt_dim=2)
    assert len(kpts2) == 4
    assert kpts2[0] == (0.0, 0.0)
    assert kpts2[1] == (1.0, 0.0)
    assert kpts2[2] == (1.0, 0.5)
    assert kpts2[3] == (0.0, 0.5)

    kpts3 = ordered_corner_keypoints(corners, img_size, kpt_dim=3)
    assert len(kpts3) == 4
    assert kpts3[0] == (0.0, 0.0, 2.0)
    assert kpts3[2] == (1.0, 0.5, 2.0)


def test_format_yolo_pose_line():
    bbox = (0.5, 0.5, 0.4, 0.2)
    kpts = [(0.3, 0.4), (0.7, 0.4), (0.7, 0.6), (0.3, 0.6)]
    line = format_yolo_pose_line(1, bbox, kpts)
    tokens = line.strip().split()
    assert tokens[0] == "1"
    assert len(tokens) == 1 + 4 + 8


def test_roboflow_license_audit(tmp_path: Path):
    licenses_file = tmp_path / "DATA_LICENSES.md"
    licenses_file.write_text(
        "# Data Licenses\n\n- test-usmly/philippine-ids-kmiv4: CC BY 4.0\n",
        encoding="utf-8",
    )

    assert audit_roboflow_license("test-usmly/philippine-ids-kmiv4", licenses_file) is True

    with pytest.raises(PermissionError, match="not recorded"):
        audit_roboflow_license("unrecorded/malicious-dataset", licenses_file)


def test_metadata_roundtrip(tmp_path: Path):
    meta = build_model_metadata(
        class_names=["philsys", "lto", "payment_card"],
        input_size=[640, 640],
        keypoint_order=DEFAULT_KEYPOINT_ORDER,
        quantization="int8",
    )
    meta_path = tmp_path / "metadata.json"
    save_model_metadata(meta, meta_path)

    loaded = load_model_metadata(meta_path)
    assert loaded["class_names"] == DEFAULT_DOCUMENT_CLASSES
    assert loaded["input_size"] == [640, 640]
    assert loaded["quantization"] == "int8"


def test_convert_d7_dataset_end_to_end(tmp_path: Path):
    # Construct mock D7 benchmark dataset
    d7_dir = tmp_path / "d7"
    img_dir = d7_dir / "images"
    lbl_dir = d7_dir / "labels"
    img_dir.mkdir(parents=True)
    lbl_dir.mkdir(parents=True)

    # 1. Positive sample (PhilSys, train split)
    img1 = img_dir / "sample_001.png"
    img1.write_bytes(b"\x89PNG\r\n\x1a\nfake")
    lbl1 = lbl_dir / "sample_001.json"
    lbl1.write_text(
        json.dumps({
            "class": "philsys",
            "document_class": "philsys",
            "variant": "philsys_front",
            "image_size": [1000, 600],
            "card_corners": [[100, 100], [900, 100], [900, 500], [100, 500]],
            "is_negative": False,
        }),
        encoding="utf-8",
    )

    # 2. Negative sample (val split)
    img2 = img_dir / "sample_002.png"
    img2.write_bytes(b"\x89PNG\r\n\x1a\nfake")
    lbl2 = lbl_dir / "sample_002.json"
    lbl2.write_text(
        json.dumps({
            "class": "negative",
            "document_class": "negative",
            "variant": "negative-receipt",
            "image_size": [800, 800],
            "card_corners": [],
            "is_negative": True,
        }),
        encoding="utf-8",
    )

    # Manifest
    manifest_csv = d7_dir / "manifest.csv"
    manifest_csv.write_text(
        "split,image_path,label_path,base_image_id,class,document_class,variant,is_negative\n"
        "train,images/sample_001.png,labels/sample_001.json,sample_001,philsys,philsys,philsys_front,False\n"
        "val,images/sample_002.png,labels/sample_002.json,sample_002,negative,negative,negative-receipt,True\n",
        encoding="utf-8",
    )

    yolo_dir = tmp_path / "yolo"
    res = convert_d7_dataset(d7_dir, yolo_dir)

    assert res["counts"]["train"] == 1
    assert res["counts"]["val"] == 1

    # Verify generated positive YOLO label
    train_txt = yolo_dir / "labels" / "train" / "sample_001.txt"
    assert train_txt.is_file()
    lines = train_txt.read_text().splitlines()
    assert len(lines) == 1
    tokens = lines[0].split()
    assert tokens[0] == "0"  # philsys class index

    # Verify generated negative YOLO label (empty file for background)
    val_txt = yolo_dir / "labels" / "val" / "sample_002.txt"
    assert val_txt.is_file()
    assert val_txt.read_text() == ""

    # Verify dataset.yaml
    yaml_file = yolo_dir / "dataset.yaml"
    assert yaml_file.is_file()
    with open(yaml_file) as yf:
        data = yaml.safe_load(yf)
    assert data["kpt_shape"] == [4, 2]
    assert data["names"][0] == "philsys"
