from pydantic import BaseModel


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

