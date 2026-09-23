import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request

from app.config import settings
from app.model import EmbeddingModelService
from app.schemas import (
    EmbeddingRequest,
    EmbeddingResponse,
    HealthResponse
)
from FlagEmbedding import FlagReranker
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """在服务生命周期内初始化并持有 Embedding 模型。"""
    logger.info("Loading embedding model: %s", settings.MODEL_NAME)

    app.state.embedding_service = EmbeddingModelService()

    logger.info(
        "Embedding model loaded, dimension=%s",
        settings.DIMENSION
    )

    yield

    app.state.embedding_service = None


app = FastAPI(
    title="Embedding Service",
    version="1.0.0",
    lifespan=lifespan
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """检查 Embedding 服务及模型是否正常。"""
    return HealthResponse(
        status="ok",
        model=settings.MODEL_NAME,
        dimension=settings.DIMENSION
    )


@app.post("/embed", response_model=EmbeddingResponse)
def embed(
    payload: EmbeddingRequest,
    request: Request
) -> EmbeddingResponse:
    """批量生成文本 Dense Embedding。"""
    try:
        service: EmbeddingModelService = (
            request.app.state.embedding_service
        )

        vectors = service.embed(payload.texts)

        return EmbeddingResponse(
            dimension=settings.DIMENSION,
            vectors=vectors
        )

    except Exception as exc:
        logger.exception("Embedding failed")

        raise HTTPException(
            status_code=500,
            detail=str(exc)
        ) from exc


@app.post("/debug")
async def debug(request: Request):
    """返回收到的原始 HTTP 请求体。"""
    body = await request.body()
    return {
        "content_type": request.headers.get("content-type"),
        "body": body.decode("utf-8")
    }

class RerankRequest(BaseModel):
    """重排请求。"""
    query: str
    documents: list[str]

reranker = FlagReranker(
    "BAAI/bge-reranker-v2-m3",
    use_fp16=True
)

def rerank(query: str, documents: list[str]) -> list[float]:
    """计算查询与候选文档的相关性分数。"""
    pairs = [[query, document] for document in documents]
    scores = reranker.compute_score(pairs, normalize=True)
    return scores if isinstance(scores, list) else [scores]

@app.post("/rerank")
def rerank_api(request: RerankRequest):
    """对候选文档进行相关性重排。"""
    return {
        "scores": rerank(request.query, request.documents)
    }