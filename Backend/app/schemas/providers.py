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


class ProviderConfigCreateRequest(BaseModel):
    id: str = Field(min_length=2, max_length=80)
    provider_type: str = Field(min_length=2, max_length=16)
    purpose: str = Field(min_length=2, max_length=80)
    provider_name: str = Field(min_length=2, max_length=80)
    model_name: str = Field(min_length=2, max_length=120)
    priority: int = Field(ge=1, le=10_000)
    region: str = Field(default="cn", min_length=2, max_length=16)
    enabled: bool = True


class ProviderConfigUpdateRequest(BaseModel):
    provider_type: str | None = Field(default=None, min_length=2, max_length=16)
    purpose: str | None = Field(default=None, min_length=2, max_length=80)
    provider_name: str | None = Field(default=None, min_length=2, max_length=80)
    model_name: str | None = Field(default=None, min_length=2, max_length=120)
    priority: int | None = Field(default=None, ge=1, le=10_000)
    region: str | None = Field(default=None, min_length=2, max_length=16)
    enabled: bool | None = None
