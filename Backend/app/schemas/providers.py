from pydantic import BaseModel, Field


class ProviderSelectionRequest(BaseModel):
    provider_type: str
    purpose: str


class ProviderSelectionResponse(BaseModel):
    id: str
    provider_type: str
    purpose: str
    provider_name: str
    model_name: str
    priority: int
    region: str
    enabled: bool = True
    has_api_key: bool = False
    api_key_preview: str | None = None


class ProviderConfigUpdateRequest(BaseModel):
    priority: int | None = Field(default=None, ge=1, le=10_000)
    enabled: bool | None = None
    api_key: str | None = Field(default=None, max_length=4096)


class ProviderConnectivityTestResponse(BaseModel):
    id: str
    provider_type: str
    provider_name: str
    model_name: str
    success: bool
    detail: str
    message: str | None = None
