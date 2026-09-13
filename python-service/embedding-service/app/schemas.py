from pydantic import BaseModel, Field


class EmbeddingRequest(BaseModel):
    """定义批量向量化请求。"""

    texts: list[str] = Field(min_length=1, max_length=64)


class EmbeddingResponse(BaseModel):
    """定义批量向量化响应。"""

    dimension: int
    vectors: list[list[float]]


class HealthResponse(BaseModel):
    """定义服务健康检查响应。"""

    status: str
    model: str
    dimension: int