from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, UploadFile, File, HTTPException
from docling.document_converter import DocumentConverter

app = FastAPI(title="Parser Service")

UPLOAD_DIR = Path("uploads")  # 当前工作目录/uploads
UPLOAD_DIR.mkdir(exist_ok=True)

converter = DocumentConverter()

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/parse")
async def parser(file: UploadFile = File(...)): # 入参file来自上传文件，且必填
    if not file.filename:
        raise HTTPException(status_code=400, detail="Filename cannot be empty")

    suffix = Path(file.filename).suffix.lower()
    if suffix not in [".docx", ".pdf", ".txt", ".md", ".markdown"]:
        raise HTTPException(status_code=400, detail=f"File type not supported: {suffix}")

    save_path = UPLOAD_DIR / f"{uuid4()}{suffix}"  # uuid4生成随机UUID，避免文件名重复和违规等问题

    content = await file.read()
    save_path.write_bytes(content)

    try:
        if suffix in [".txt", ".md", ".markdown"]:
            text = save_path.read_text(encoding="utf-8")
        else:
            result = converter.convert(str(save_path))  # 将文档解析为docling内部的结构化文档
            text = result.document.export_to_markdown()  # 转换成markdown

        return {
            "filename": file.filename,
            "content": text
        }

    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))