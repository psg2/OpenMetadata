# generated by datamodel-codegen:
#   filename:  data/tags/tierTags.json
#   timestamp: 2021-10-31T08:53:25+00:00

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class Model(BaseModel):
    __root__: Any = Field(
        ...,
        description='Tags related to tiering of the data. Tiers capture the business importance of data. When a data asset is tagged with `Tier` tag, all the upstream data assets used for producing it will also be labeled with the same tag. This will help upstream data asset owners to understand the critical purposes their data is being used.',
    )
