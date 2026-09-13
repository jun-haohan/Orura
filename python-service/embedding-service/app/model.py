import threading

import numpy as np
import torch
from FlagEmbedding import BGEM3FlagModel

from app.config import settings


class EmbeddingModelService:
    """负责加载 BGE-M3 并生成 Dense Embedding。"""

    def __init__(self):
        """初始化并加载 BGE-M3 模型。"""
        self.use_fp16 = torch.cuda.is_available()

        self.model = BGEM3FlagModel(
            settings.MODEL_NAME,
            use_fp16=self.use_fp16
        )

        self.lock = threading.Lock()

    def embed(self, texts: list[str]) -> list[list[float]]:
        """批量生成文本的 Dense Embedding。"""
        if not texts:
            raise ValueError("texts cannot be empty")

        with self.lock:
            output = self.model.encode(
                texts,
                batch_size=settings.BATCH_SIZE,
                max_length=settings.MAX_LENGTH,
                return_dense=True,
                return_sparse=False,
                return_colbert_vecs=False
            )

        vectors = np.asarray(
            output["dense_vecs"],
            dtype=np.float32
        )

        if vectors.ndim != 2:
            raise RuntimeError("Invalid embedding result")

        if vectors.shape[1] != settings.DIMENSION:
            raise RuntimeError(
                f"Embedding dimension mismatch: "
                f"expected={settings.DIMENSION}, "
                f"actual={vectors.shape[1]}"
            )

        return vectors.tolist()