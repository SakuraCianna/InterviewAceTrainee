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


class ProviderConfigCreateRequest(BaseModel):
    id: str = Field(min_length=2, max_length=80)
    provider_type: str = Field(min_length=2, max_length=16)
    purpose: str = Field(min_length=2, max_length=80)
    provider_name: str = Field(min_length=2, max_length=80)
    model_name: str = Field(min_length=2, max_length=120)
    priority: int = Field(ge=1, le=10_000)
    region: str = Field(default="cn", min_length=2, max_length=16)
    enabled: bool = True
    api_key: str | None = Field(default=None, max_length=4096)


class ProviderConfigUpdateRequest(BaseModel):
    provider_type: str | None = Field(default=None, min_length=2, max_length=16)
    purpose: str | None = Field(default=None, min_length=2, max_length=80)
    provider_name: str | None = Field(default=None, min_length=2, max_length=80)
    model_name: str | None = Field(default=None, min_length=2, max_length=120)
    priority: int | None = Field(default=None, ge=1, le=10_000)
    region: str | None = Field(default=None, min_length=2, max_length=16)
    enabled: bool | None = None
    api_key: str | None = Field(default=None, max_length=4096)


class ProviderConnectivityTestResponse(BaseModel):
    id: str
    provider_type: str
    provider_name: str
    model_name: str
    success: bool
    detail: str
