import os


class Settings:
    """Embedding 服务配置。"""

    MODEL_NAME = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
    DIMENSION = int(os.getenv("EMBEDDING_DIMENSION", "1024"))
    BATCH_SIZE = int(os.getenv("EMBEDDING_BATCH_SIZE", "16"))
    MAX_LENGTH = int(os.getenv("EMBEDDING_MAX_LENGTH", "2048"))


settings = Settings()