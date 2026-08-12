"""配置 Schema 单元测试."""

from __future__ import annotations

from pathlib import Path

from chunker.config import (
    ChunkerSettings,
    ModalityDefaults,
    get_settings,
    reset_settings,
)
from chunker.exceptions import ChunkerConfigError
from chunker.models import ChunkConfig, Modality
from pydantic import ValidationError
import pytest

# ----------------------------------------------------------------------
# ModalityDefaults
# ----------------------------------------------------------------------


class TestModalityDefaults:
    def test_default_text(self):
        d = ModalityDefaults()
        assert d.text["modality"] == "text"
        assert d.text["windowSize"] == 512

    def test_default_table(self):
        d = ModalityDefaults()
        assert d.table["modality"] == "table"
        assert d.table["windowSize"] == 50

    def test_default_image(self):
        d = ModalityDefaults()
        assert d.image["modality"] == "image"

    def test_default_audio(self):
        d = ModalityDefaults()
        assert d.audio["modality"] == "audio"

    def test_default_video(self):
        d = ModalityDefaults()
        assert d.video["modality"] == "video"

    def test_default_code(self):
        d = ModalityDefaults()
        assert d.code["modality"] == "code"

    def test_get_with_enum(self):
        d = ModalityDefaults()
        cfg = d.get(Modality.TEXT)
        assert cfg["modality"] == "text"

    def test_get_with_string(self):
        d = ModalityDefaults()
        cfg = d.get("image")
        assert cfg["modality"] == "image"

    def test_get_unknown_raises(self):
        d = ModalityDefaults()
        with pytest.raises(ChunkerConfigError):
            d.get("unknown")


# ----------------------------------------------------------------------
# ChunkerSettings 基础
# ----------------------------------------------------------------------


class TestChunkerSettings:
    def test_default(self):
        s = ChunkerSettings()
        assert s.host == "0.0.0.0"
        assert s.port == 8090
        assert s.logLevel == "info"
        assert s.defaultModality == "text"
        assert s.maxChunks == 10000
        assert s.enableEmbedding is False
        assert s.tokenizer == "tiktoken"
        assert isinstance(s.modalityDefaults, ModalityDefaults)

    def test_invalid_log_level(self):
        with pytest.raises(ValidationError):
            ChunkerSettings(logLevel="invalid")

    def test_valid_log_levels(self):
        for lvl in ["debug", "info", "warning", "error", "critical"]:
            s = ChunkerSettings(logLevel=lvl)
            assert s.logLevel == lvl

    def test_log_level_case_insensitive(self):
        s = ChunkerSettings(logLevel="INFO")
        assert s.logLevel == "info"

    def test_invalid_default_modality(self):
        with pytest.raises(ValidationError):
            ChunkerSettings(defaultModality="unknown")

    def test_default_modality_case_insensitive(self):
        s = ChunkerSettings(defaultModality="TEXT")
        assert s.defaultModality == "text"

    def test_port_range(self):
        with pytest.raises(ValidationError):
            ChunkerSettings(port=0)
        with pytest.raises(ValidationError):
            ChunkerSettings(port=70000)

    def test_max_chunks_positive(self):
        with pytest.raises(ValidationError):
            ChunkerSettings(maxChunks=0)


# ----------------------------------------------------------------------
# get_default_config
# ----------------------------------------------------------------------


class TestGetDefaultConfig:
    def test_text(self):
        s = ChunkerSettings()
        cfg = s.get_default_config(Modality.TEXT)
        assert isinstance(cfg, ChunkConfig)
        assert cfg.modality is Modality.TEXT
        assert cfg.windowSize == 512

    def test_image(self):
        s = ChunkerSettings()
        cfg = s.get_default_config("image")
        assert cfg.modality is Modality.IMAGE

    def test_table(self):
        s = ChunkerSettings()
        cfg = s.get_default_config(Modality.TABLE)
        assert cfg.modality is Modality.TABLE
        assert cfg.windowSize == 50

    def test_audio(self):
        s = ChunkerSettings()
        cfg = s.get_default_config(Modality.AUDIO)
        assert cfg.modality is Modality.AUDIO

    def test_video(self):
        s = ChunkerSettings()
        cfg = s.get_default_config(Modality.VIDEO)
        assert cfg.modality is Modality.VIDEO

    def test_code(self):
        s = ChunkerSettings()
        cfg = s.get_default_config(Modality.CODE)
        assert cfg.modality is Modality.CODE


# ----------------------------------------------------------------------
# 环境变量加载
# ----------------------------------------------------------------------


class TestEnvLoading:
    def test_env_override(self, monkeypatch):
        # pydantic-settings v2 对 camelCase 字段使用无下划线环境变量名
        # logLevel -> CHUNKER_LOGLEVEL, enableEmbedding -> CHUNKER_ENABLEEMBEDDING
        monkeypatch.setenv("CHUNKER_HOST", "127.0.0.1")
        monkeypatch.setenv("CHUNKER_PORT", "9999")
        monkeypatch.setenv("CHUNKER_LOGLEVEL", "debug")
        monkeypatch.setenv("CHUNKER_ENABLEEMBEDDING", "true")
        s = ChunkerSettings()
        assert s.host == "127.0.0.1"
        assert s.port == 9999
        assert s.logLevel == "debug"
        assert s.enableEmbedding is True

    def test_env_case_insensitive(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_LOGLEVEL", "WARNING")
        s = ChunkerSettings()
        assert s.logLevel == "warning"

    def test_env_default_modality(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_DEFAULTMODALITY", "image")
        s = ChunkerSettings()
        assert s.defaultModality == "image"

    def test_env_max_chunks(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_MAXCHUNKS", "200")
        s = ChunkerSettings()
        assert s.maxChunks == 200

    def test_env_tokenizer(self, monkeypatch):
        monkeypatch.setenv("CHUNKER_TOKENIZER", "sentencepiece")
        s = ChunkerSettings()
        assert s.tokenizer == "sentencepiece"


# ----------------------------------------------------------------------
# YAML 加载
# ----------------------------------------------------------------------


class TestYamlLoading:
    def test_from_yaml_basic(self, tmp_path: Path):
        yaml_file = tmp_path / "config.yaml"
        yaml_file.write_text(
            """
host: 127.0.0.1
port: 8888
logLevel: debug
defaultModality: image
maxChunks: 500
""".strip(),
            encoding="utf-8",
        )
        s = ChunkerSettings.from_yaml(yaml_file)
        assert s.host == "127.0.0.1"
        assert s.port == 8888
        assert s.logLevel == "debug"
        assert s.defaultModality == "image"
        assert s.maxChunks == 500

    def test_from_yaml_empty(self, tmp_path: Path):
        yaml_file = tmp_path / "empty.yaml"
        yaml_file.write_text("", encoding="utf-8")
        s = ChunkerSettings.from_yaml(yaml_file)
        assert s.host == "0.0.0.0"

    def test_from_yaml_not_exist(self, tmp_path: Path):
        with pytest.raises(ChunkerConfigError):
            ChunkerSettings.from_yaml(tmp_path / "nope.yaml")

    def test_from_yaml_invalid_syntax(self, tmp_path: Path):
        yaml_file = tmp_path / "bad.yaml"
        yaml_file.write_text(": : : invalid yaml", encoding="utf-8")
        with pytest.raises(ChunkerConfigError):
            ChunkerSettings.from_yaml(yaml_file)

    def test_from_yaml_invalid_value(self, tmp_path: Path):
        yaml_file = tmp_path / "bad.yaml"
        yaml_file.write_text("port: 99999\n", encoding="utf-8")
        with pytest.raises(ChunkerConfigError):
            ChunkerSettings.from_yaml(yaml_file)

    def test_from_yaml_with_modality_defaults(self, tmp_path: Path):
        yaml_file = tmp_path / "config.yaml"
        yaml_file.write_text(
            """
modalityDefaults:
  text:
    modality: text
    windowSize: 256
    overlap: 0.2
    maxTokens: 4096
""".strip(),
            encoding="utf-8",
        )
        s = ChunkerSettings.from_yaml(yaml_file)
        cfg = s.get_default_config(Modality.TEXT)
        assert cfg.windowSize == 256
        assert cfg.overlap == 0.2


# ----------------------------------------------------------------------
# 全局单例
# ----------------------------------------------------------------------


class TestSingleton:
    def test_get_settings_cached(self):
        reset_settings()
        s1 = get_settings()
        s2 = get_settings()
        assert s1 is s2

    def test_reset_settings(self):
        reset_settings()
        s1 = get_settings()
        reset_settings()
        s2 = get_settings()
        assert s1 is not s2

    def test_get_settings_respects_env(self, monkeypatch):
        reset_settings()
        monkeypatch.setenv("CHUNKER_PORT", "7777")
        s = get_settings()
        assert s.port == 7777
        reset_settings()
